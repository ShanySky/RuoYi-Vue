package com.ruoyi.ai.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiCheckpoint;
import com.ruoyi.ai.domain.AiConversation;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.AiModel;
import com.ruoyi.ai.domain.AiPendingToolCall;
import com.ruoyi.ai.domain.AiPrompt;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.dto.AiChatTurnRequest;
import com.ruoyi.ai.dto.AiChatTurnResponse;
import com.ruoyi.ai.dto.AiToolResultRequest;
import com.ruoyi.ai.mapper.AiConversationMapper;
import com.ruoyi.ai.mapper.AiMessageMapper;
import com.ruoyi.ai.mapper.AiPendingToolCallMapper;
import com.ruoyi.ai.tool.AiFrontendToolCallback;
import com.ruoyi.ai.tool.AiFrontendToolPolicy;
import com.ruoyi.ai.tool.AiFrontendToolPolicy.ApprovedTool;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;

@Service
public class AiAgentLoopService
{
    private static final int MAX_TOOL_RESULTS_PER_TURN = 16;
    private static final int MAX_PAGE_CONTEXT_CHARS = 20000;
    private static final int MAX_TOOL_RESULT_CHARS = 20000;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiPendingToolCallMapper pendingMapper;
    private final AiFrontendToolPolicy toolPolicy;
    private final AiAgentModelFactory modelFactory;
    private final AiConfigService configService;
    private final AiPreferenceService preferenceService;
    private final AiPromptService promptService;
    private final AiRunService runService;
    private final AiContextService contextService;
    private final ObjectMapper objectMapper;

    public AiAgentLoopService(AiConversationMapper conversationMapper, AiMessageMapper messageMapper,
            AiPendingToolCallMapper pendingMapper, AiFrontendToolPolicy toolPolicy, AiAgentModelFactory modelFactory,
            AiConfigService configService, AiPreferenceService preferenceService, AiPromptService promptService,
            AiRunService runService, AiContextService contextService, ObjectMapper objectMapper)
    {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.pendingMapper = pendingMapper;
        this.toolPolicy = toolPolicy;
        this.modelFactory = modelFactory;
        this.configService = configService;
        this.preferenceService = preferenceService;
        this.promptService = promptService;
        this.runService = runService;
        this.contextService = contextService;
        this.objectMapper = objectMapper;
    }

    public AiChatTurnResponse turn(AiChatTurnRequest request)
    {
        validateTurnRequest(request);
        Long userId = SecurityUtils.getUserId();
        AiConversation conversation = resolveConversation(request, userId);
        TurnSelection selection;
        AiRun run;

        if (request.getToolResult() != null)
        {
            AiPendingToolCall pending = acceptToolResult(conversation, userId, request);
            run = runService.requireOwned(pending.getRunId(), userId);
            if (!"WAITING_TOOL".equals(run.getStatus()))
            {
                return AiChatTurnResponse.state(conversation.getConversationId(), run.getRunId(), run.getStatus());
            }
            runService.resumeTool(run.getRunId());
            selection = new TurnSelection(pending.getModelId(), pending.getModelCode(), pending.getReasoningEffort());
        }
        else
        {
            selection = resolveUserSelection(request, conversation);
            AiPrompt systemPrompt = promptService.require(AiPromptService.SYSTEM);
            AiPrompt compactionPrompt = promptService.require(AiPromptService.COMPACTION);
            run = runService.start(conversation, request.getClientRunKey(), selection.modelId(), selection.modelCode(),
                    selection.reasoningEffort(), systemPrompt.getVersionNo(), compactionPrompt.getVersionNo());

            conversationMapper.updateSelection(conversation.getConversationId(), userId, selection.modelId(),
                    selection.reasoningEffort());
            conversation.setModelId(selection.modelId());
            conversation.setReasoningEffort(selection.reasoningEffort());
            insertMessage(conversation.getConversationId(), run.getRunId(), "USER",
                    trimTo(request.getUserMessage(), 12000), null, null, null, selection);
        }

        conversationMapper.touch(conversation.getConversationId(), userId, trimTo(request.getRoute(), 255));
        try
        {
            return callModel(conversation, request, selection, run);
        }
        catch (ServiceException e)
        {
            AiRun latest = runService.get(run.getRunId());
            if (latest != null && ("RUNNING".equals(latest.getStatus()) || "WAITING_TOOL".equals(latest.getStatus())))
            {
                runService.fail(run.getRunId(), safeMessage(e));
            }
            throw e;
        }
    }

    private TurnSelection resolveUserSelection(AiChatTurnRequest request, AiConversation conversation)
    {
        Long selectedModelId = request.getModelId() != null ? request.getModelId() : conversation.getModelId();
        if (selectedModelId == null)
        {
            AiModel preferred = preferenceService.preferredModel();
            if (preferred == null)
            {
                throw new ServiceException("没有可用的默认 AI 模型，请先完成模型配置");
            }
            selectedModelId = preferred.getModelId();
        }

        AiModel model = configService.requireEnabledSystemModel(selectedModelId);
        boolean sameModel = selectedModelId.equals(conversation.getModelId());
        String requestedEffort = request.getReasoningEffort();
        String effort;
        if (requestedEffort != null)
        {
            effort = configService.resolveReasoningEffort(model, requestedEffort);
        }
        else if (sameModel && conversation.getReasoningEffort() != null)
        {
            effort = configService.resolveReasoningEffort(model, conversation.getReasoningEffort());
        }
        else
        {
            effort = preferenceService.preferredReasoning(model);
        }
        return new TurnSelection(model.getModelId(), model.getModelCode(), effort);
    }

    private AiChatTurnResponse callModel(AiConversation conversation, AiChatTurnRequest request,
            TurnSelection selection, AiRun run)
    {
        configService.requireAgentRuntimeEnabled();
        if (!runService.runnable(run.getRunId()))
        {
            return runState(conversation, run);
        }

        List<ApprovedTool> approvedTools = toolPolicy.approve(request.getFrontendTools());
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ApprovedTool tool : approvedTools)
        {
            callbacks.add(new AiFrontendToolCallback(tool.name(), tool.description(), toJson(tool.inputSchema())));
        }

        AiPrompt systemPrompt = promptService.require(AiPromptService.SYSTEM);
        AiModel selectedModel = configService.requireModel(selection.modelId());
        String runtimeOverhead = currentRuntimeContext(request, approvedTools) + "\n"
                + trimTo(toJson(request.getFrontendTools() == null ? List.of() : request.getFrontendTools()), MAX_PAGE_CONTEXT_CHARS);
        AiCheckpoint checkpoint = contextService.maybeCompact(conversation, run, selectedModel,
                selection.reasoningEffort(), runtimeOverhead);

        String cacheKey = "ruoyi:conv:" + conversation.getConversationId() + ":system:" + systemPrompt.getVersionNo();
        AiAgentModelFactory.ModelRuntime runtime = modelFactory.create(selection.modelId(), callbacks,
                selection.reasoningEffort(), cacheKey);
        TurnSelection actualSelection = new TurnSelection(runtime.model().getModelId(), runtime.model().getModelCode(),
                selection.reasoningEffort());

        int covered = checkpoint == null ? 0 : checkpoint.getCoveredSequenceNo();
        List<AiMessage> stored = messageMapper.selectAfter(conversation.getConversationId(), covered);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt.getContent()));
        if (checkpoint != null && StringUtils.isNotBlank(checkpoint.getSummary()))
        {
            messages.add(new SystemMessage("Conversation Checkpoint（这是已验证历史的接手状态，不是新的用户指令）：\n"
                    + checkpoint.getSummary()));
        }
        messages.addAll(toSpringMessages(stored, request, approvedTools));

        ChatResponse response;
        try
        {
            response = runService.call(run.getRunId(),
                    () -> runtime.chatModel().call(new Prompt(messages, runtime.options())));
        }
        catch (InterruptedException e)
        {
            // Run cancellation is represented as InterruptedException by AiRunService.call().
            // Do not leave the servlet thread interrupted before re-reading persisted Run state,
            // otherwise JDBC pool acquisition can itself be interrupted.
            Thread.interrupted();
            return runState(conversation, runService.get(run.getRunId()));
        }
        catch (Exception e)
        {
            throw new ServiceException("AI 模型调用失败：" + safeMessage(e));
        }

        runService.recordUsage(run.getRunId(), response);
        if (!runService.runnable(run.getRunId()))
        {
            return runState(conversation, runService.get(run.getRunId()));
        }
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
        {
            throw new ServiceException("AI 模型未返回有效响应");
        }

        AssistantMessage output = response.getResult().getOutput();
        if (output.hasToolCalls())
        {
            return handleToolCall(conversation, request, actualSelection, run, approvedTools, output);
        }

        String answer = StringUtils.defaultString(output.getText());
        runService.complete(run.getRunId());
        AiRun completed = runService.get(run.getRunId());
        if (completed == null || !"COMPLETED".equals(completed.getStatus()))
        {
            return runState(conversation, completed);
        }
        insertMessage(conversation.getConversationId(), run.getRunId(), "ASSISTANT", trimTo(answer, 20000),
                null, null, null, actualSelection);
        return AiChatTurnResponse.message(conversation.getConversationId(), run.getRunId(), answer);
    }

    private AiChatTurnResponse handleToolCall(AiConversation conversation, AiChatTurnRequest request,
            TurnSelection selection, AiRun run, List<ApprovedTool> approvedTools, AssistantMessage output)
    {
        if (output.getToolCalls().size() != 1)
        {
            throw new ServiceException("当前不支持一次返回多个页面工具调用");
        }
        if (messageMapper.countToolResultsInCurrentTurn(conversation.getConversationId()) >= MAX_TOOL_RESULTS_PER_TURN)
        {
            throw new ServiceException("本轮工具调用次数已超过限制");
        }

        AssistantMessage.ToolCall modelCall = output.getToolCalls().get(0);
        ApprovedTool approved = approvedTools.stream().filter(t -> t.name().equals(modelCall.name())).findFirst()
                .orElseThrow(() -> new ServiceException("模型请求了当前页面不可用的工具：" + modelCall.name()));

        runService.waitingTool(run.getRunId());
        String runtimeCallId = "rtc_" + UUID.randomUUID().toString().replace("-", "");
        insertMessage(conversation.getConversationId(), run.getRunId(), "ASSISTANT", trimTo(output.getText(), 12000),
                runtimeCallId, modelCall.name(), trimTo(modelCall.arguments(), 20000), selection);

        AiPendingToolCall pending = new AiPendingToolCall();
        pending.setCallId(runtimeCallId);
        pending.setConversationId(conversation.getConversationId());
        pending.setUserId(conversation.getUserId());
        pending.setToolName(modelCall.name());
        pending.setArgumentsJson(trimTo(modelCall.arguments(), 20000));
        pending.setRiskLevel(approved.riskLevel());
        pending.setModelId(selection.modelId());
        pending.setModelCode(selection.modelCode());
        pending.setReasoningEffort(selection.reasoningEffort());
        pending.setRunId(run.getRunId());
        pending.setRoute(trimTo(request.getRoute(), 255));
        pending.setPageInstanceId(trimTo(request.getPageInstanceId(), 64));
        pending.setPageVersion(request.getPageVersion());
        try
        {
            pendingMapper.insert(pending);
        }
        catch (Exception e)
        {
            runService.fail(run.getRunId(), "TOOL_STATE_SAVE_FAILED");
            throw new ServiceException("AI 页面工具调用状态保存失败，请重试");
        }

        AiChatTurnResponse.ToolCall call = new AiChatTurnResponse.ToolCall();
        call.setCallId(runtimeCallId);
        call.setName(modelCall.name());
        call.setArguments(modelCall.arguments());
        call.setRiskLevel(approved.riskLevel());
        call.setDescription(approved.description());
        return AiChatTurnResponse.toolCall(conversation.getConversationId(), run.getRunId(), call);
    }

    private AiPendingToolCall acceptToolResult(AiConversation conversation, Long userId, AiChatTurnRequest request)
    {
        AiToolResultRequest result = request.getToolResult();
        AiPendingToolCall pending = pendingMapper.selectByCall(conversation.getConversationId(), result.getCallId());
        if (pending == null || !"PENDING".equals(pending.getStatus()))
        {
            throw new ServiceException("待处理的页面工具调用不存在、已完成、已取消或已过期");
        }
        if (!userId.equals(pending.getUserId()))
        {
            throw new ServiceException("不能提交其他用户的页面工具结果");
        }
        AiRun run = runService.requireOwned(pending.getRunId(), userId);
        if (!"WAITING_TOOL".equals(run.getStatus()))
        {
            throw new ServiceException("当前 Run 已停止或被新指令替代");
        }

        if (!isNavigationTool(pending.getToolName()))
        {
            if (StringUtils.isNotBlank(pending.getRoute()) && !Objects.equals(pending.getRoute(), request.getRoute()))
            {
                throw new ServiceException("页面已经切换，旧页面工具结果已失效");
            }
            if (StringUtils.isNotBlank(pending.getPageInstanceId())
                    && !Objects.equals(pending.getPageInstanceId(), request.getPageInstanceId()))
            {
                throw new ServiceException("页面实例已经刷新，旧页面工具结果已失效");
            }
            if (pending.getPageVersion() != null && !Objects.equals(pending.getPageVersion(), request.getPageVersion()))
            {
                throw new ServiceException("页面能力版本已经变化，旧页面工具结果已失效");
            }
        }

        AiFrontendToolPolicy.ToolPolicy policy;
        try
        {
            policy = toolPolicy.requirePolicy(pending.getToolName());
        }
        catch (IllegalArgumentException e)
        {
            throw new ServiceException("页面工具不在服务端允许列表中");
        }

        List<com.ruoyi.ai.dto.AiFrontendToolDefinition> one = new ArrayList<>();
        com.ruoyi.ai.dto.AiFrontendToolDefinition def = new com.ruoyi.ai.dto.AiFrontendToolDefinition();
        def.setName(pending.getToolName());
        def.setDescription(policy.defaultDescription());
        def.setInputSchema(Map.of("type", "object", "properties", Map.of()));
        one.add(def);
        if (toolPolicy.approve(one).isEmpty())
        {
            throw new ServiceException("当前用户已无权完成此页面工具调用");
        }
        if (pendingMapper.resolve(pending.getPendingId()) != 1)
        {
            throw new ServiceException("页面工具调用状态已变化，请重试");
        }

        Map<String, Object> pageRuntime = new LinkedHashMap<>();
        pageRuntime.put("route", request.getRoute());
        pageRuntime.put("pageInstanceId", request.getPageInstanceId());
        pageRuntime.put("pageVersion", request.getPageVersion());
        pageRuntime.put("pageContext", request.getPageContext());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", Boolean.TRUE.equals(result.getSuccess()));
        payload.put("result", result.getResult());
        payload.put("error", trimTo(result.getError(), 2000));
        payload.put("pageRuntime", pageRuntime);
        String resultJson = trimTo(toJson(payload), MAX_TOOL_RESULT_CHARS);
        TurnSelection selection = new TurnSelection(pending.getModelId(), pending.getModelCode(),
                pending.getReasoningEffort());
        insertMessage(conversation.getConversationId(), pending.getRunId(), "TOOL", resultJson,
                pending.getCallId(), pending.getToolName(), null, selection);
        return pending;
    }

    private AiConversation resolveConversation(AiChatTurnRequest request, Long userId)
    {
        if (request.getConversationId() == null)
        {
            if (request.getToolResult() != null)
            {
                throw new ServiceException("Tool Result 必须提供 conversationId");
            }

            Long initialModelId = request.getModelId();
            if (initialModelId == null)
            {
                AiModel preferred = preferenceService.preferredModel();
                if (preferred == null)
                {
                    throw new ServiceException("新会话必须选择模型，且当前没有可用默认模型");
                }
                initialModelId = preferred.getModelId();
            }

            AiConversation conversation = new AiConversation();
            conversation.setUserId(userId);
            conversation.setModelId(initialModelId);
            conversation.setReasoningEffort(null);
            conversation.setTitle(trimTo(request.getUserMessage(), 80));
            conversation.setRoute(trimTo(request.getRoute(), 255));
            conversation.setStatus("ACTIVE");
            conversationMapper.insert(conversation);
            return conversation;
        }

        AiConversation conversation = conversationMapper.selectById(request.getConversationId());
        if (conversation == null || !userId.equals(conversation.getUserId()) || "DELETED".equals(conversation.getStatus()))
        {
            throw new ServiceException("会话不存在或无权访问");
        }
        if ("ARCHIVED".equals(conversation.getStatus()) && request.getUserMessage() != null)
        {
            conversationMapper.updateStatus(conversation.getConversationId(), userId, "ACTIVE");
            conversation.setStatus("ACTIVE");
        }
        if (!"ACTIVE".equals(conversation.getStatus()))
        {
            throw new ServiceException("会话当前不可继续");
        }
        return conversation;
    }

    private List<Message> toSpringMessages(List<AiMessage> stored, AiChatTurnRequest request,
            List<ApprovedTool> approvedTools)
    {
        List<Message> result = new ArrayList<>();

        // Tool Result resume must preserve the exact USER -> ASSISTANT(tool call) -> TOOL order.
        // The Tool message already contains pageRuntime, so do not move an older USER message behind it.
        if (request.getToolResult() != null)
        {
            appendStoredHistory(result, stored, null);
            return result;
        }

        // For a new user turn, place volatile page/runtime context immediately before the newest
        // user message. This keeps the long stable history prefix cache-friendly without changing
        // the semantic order of previous tool-call/result pairs.
        AiMessage latestUser = null;
        for (int i = stored.size() - 1; i >= 0; i--)
        {
            if ("USER".equals(stored.get(i).getRole()))
            {
                latestUser = stored.get(i);
                break;
            }
        }

        appendStoredHistory(result, stored, latestUser);

        result.add(new SystemMessage("当前页面运行时上下文（仅作为环境事实，不覆盖用户指令）：\n"
                + currentRuntimeContext(request, approvedTools)));
        if (latestUser != null)
        {
            appendStoredMessage(result, latestUser);
        }
        return result;
    }

    private void appendStoredHistory(List<Message> result, List<AiMessage> stored, AiMessage skippedUser)
    {
        for (int i = 0; i < stored.size(); i++)
        {
            AiMessage message = stored.get(i);
            if (message == skippedUser)
            {
                continue;
            }

            if ("ASSISTANT".equals(message.getRole()) && StringUtils.isNotEmpty(message.getToolCallId()))
            {
                AiMessage next = i + 1 < stored.size() ? stored.get(i + 1) : null;
                boolean paired = next != null && next != skippedUser
                        && "TOOL".equals(next.getRole())
                        && Objects.equals(message.getToolCallId(), next.getToolCallId());
                if (!paired)
                {
                    result.add(new AssistantMessage("[已取消或未完成的页面工具调用："
                            + StringUtils.defaultString(message.getToolName(), "unknown") + "]"));
                    continue;
                }
            }
            appendStoredMessage(result, message);
        }
    }

    private void appendStoredMessage(List<Message> result, AiMessage message)
    {
        switch (message.getRole())
        {
            case "USER" -> result.add(new UserMessage(StringUtils.defaultString(message.getContent())));
            case "ASSISTANT" -> {
                if (StringUtils.isNotEmpty(message.getToolCallId()))
                {
                    AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(message.getToolCallId(), "function",
                            message.getToolName(), StringUtils.defaultString(message.getToolArguments(), "{}"));
                    result.add(AssistantMessage.builder().content(message.getContent()).toolCalls(List.of(toolCall)).build());
                }
                else
                {
                    result.add(new AssistantMessage(StringUtils.defaultString(message.getContent())));
                }
            }
            case "TOOL" -> {
                ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                        message.getToolCallId(), message.getToolName(),
                        StringUtils.defaultString(message.getContent(), "{}"));
                result.add(ToolResponseMessage.builder().responses(List.of(toolResponse)).build());
            }
            default -> throw new ServiceException("会话历史包含未知角色：" + message.getRole());
        }
    }

    private String currentRuntimeContext(AiChatTurnRequest request, List<ApprovedTool> tools)
    {
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("route", StringUtils.defaultString(request.getRoute()));
        runtime.put("pageInstanceId", request.getPageInstanceId());
        runtime.put("pageVersion", request.getPageVersion());
        runtime.put("availableTools", tools.stream().map(ApprovedTool::name).toList());
        runtime.put("pageContext", request.getPageContext() == null ? Map.of() : request.getPageContext());
        return trimTo(toJson(runtime), MAX_PAGE_CONTEXT_CHARS);
    }

    private void insertMessage(Long conversationId, Long runId, String role, String content, String toolCallId,
            String toolName, String toolArguments, TurnSelection selection)
    {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setSequenceNo(messageMapper.nextSequence(conversationId));
        message.setRole(role);
        message.setContent(content);
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        message.setToolArguments(toolArguments);
        message.setRunId(runId);
        if (selection != null)
        {
            message.setModelId(selection.modelId());
            message.setModelCode(selection.modelCode());
            message.setReasoningEffort(selection.reasoningEffort());
        }
        messageMapper.insert(message);
    }

    private AiChatTurnResponse runState(AiConversation conversation, AiRun run)
    {
        String status = run == null ? "CANCELLED" : run.getStatus();
        return AiChatTurnResponse.state(conversation.getConversationId(), run == null ? null : run.getRunId(), status);
    }

    private boolean isNavigationTool(String name)
    {
        return "app_navigate".equals(name);
    }

    private void validateTurnRequest(AiChatTurnRequest request)
    {
        boolean hasUserMessage = StringUtils.isNotBlank(request.getUserMessage());
        boolean hasToolResult = request.getToolResult() != null;
        if (hasUserMessage == hasToolResult)
        {
            throw new ServiceException("每次 turn 必须且只能包含 userMessage 或 toolResult 之一");
        }
    }

    private String toJson(Object value)
    {
        try
        {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e)
        {
            throw new ServiceException("AI 上下文 JSON 序列化失败");
        }
    }

    private String trimTo(String value, int max)
    {
        if (value == null || value.length() <= max)
        {
            return value;
        }
        return value.substring(0, max);
    }

    private String safeMessage(Exception e)
    {
        String value = StringUtils.defaultString(e.getMessage(), e.getClass().getSimpleName());
        value = value.replaceAll("(?i)(api[_ -]?key|authorization|token)\\s*[:=]\\s*[^,;\\s]+", "$1=[redacted]");
        return trimTo(value, 300);
    }

    private record TurnSelection(Long modelId, String modelCode, String reasoningEffort)
    {
    }
}
