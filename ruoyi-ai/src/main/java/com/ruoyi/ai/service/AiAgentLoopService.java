package com.ruoyi.ai.service;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.ai.domain.AiConversation;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.AiModel;
import com.ruoyi.ai.domain.AiPendingToolCall;
import com.ruoyi.ai.domain.AiPrompt;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.dto.AiChatTurnRequest;
import com.ruoyi.ai.dto.AiChatTurnResponse;
import com.ruoyi.ai.mapper.AiConversationMapper;
import com.ruoyi.ai.runtime.AgentRuntime;
import com.ruoyi.ai.runtime.AgentRuntimeRequest;
import com.ruoyi.ai.runtime.AgentRuntimeResult;
import com.ruoyi.ai.tool.AiFrontendToolPolicy.ApprovedTool;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;

@Service
public class AiAgentLoopService
{
    private final AiConversationMapper conversationMapper;
    private final AiMessageService messageService;
    private final AgentRuntime agentRuntime;
    private final AiConfigService configService;
    private final AiPreferenceService preferenceService;
    private final AiPromptService promptService;
    private final AiRunService runService;
    private final AiPromptContextAssembler contextAssembler;
    private final AiToolCallCoordinator toolCoordinator;

    public AiAgentLoopService(AiConversationMapper conversationMapper, AiMessageService messageService,
            AgentRuntime agentRuntime, AiConfigService configService, AiPreferenceService preferenceService,
            AiPromptService promptService, AiRunService runService, AiPromptContextAssembler contextAssembler,
            AiToolCallCoordinator toolCoordinator)
    {
        this.conversationMapper = conversationMapper;
        this.messageService = messageService;
        this.agentRuntime = agentRuntime;
        this.configService = configService;
        this.preferenceService = preferenceService;
        this.promptService = promptService;
        this.runService = runService;
        this.contextAssembler = contextAssembler;
        this.toolCoordinator = toolCoordinator;
    }

    public AiChatTurnResponse turn(AiChatTurnRequest request)
    {
        validateTurnRequest(request);
        toolCoordinator.validateRequest(request);
        Long userId = SecurityUtils.getUserId();
        AiConversation conversation = resolveConversation(request, userId);
        TurnSelection selection;
        AiRun run;

        if (request.getToolResult() != null)
        {
            AiPendingToolCall pending = toolCoordinator.acceptToolResult(conversation, userId, request);
            run = runService.requireOwned(pending.getRunId(), userId);
            if (!"RUNNING".equals(run.getStatus()))
            {
                return AiChatTurnResponse.state(conversation.getConversationId(), run.getRunId(), run.getStatus());
            }
            selection = new TurnSelection(pending.getModelId(), pending.getModelCode(), pending.getReasoningEffort());
        }
        else
        {
            selection = resolveUserSelection(request, conversation);
            AiPrompt systemPrompt = promptService.require(AiPromptService.SYSTEM);
            AiPrompt compactionPrompt = promptService.require(AiPromptService.COMPACTION);
            run = runService.start(conversation, request.getClientRunKey(), selection.modelId(), selection.modelCode(),
                    selection.reasoningEffort(), systemPrompt.getVersionNo(), compactionPrompt.getVersionNo(),
                    trimTo(request.getUserMessage(), 12000));

            conversationMapper.updateSelection(conversation.getConversationId(), userId, selection.modelId(),
                    selection.reasoningEffort());
            conversation.setModelId(selection.modelId());
            conversation.setReasoningEffort(selection.reasoningEffort());
            if ("新会话".equals(conversation.getTitle()) && StringUtils.isNotBlank(request.getUserMessage()))
            {
                String initialTitle = trimTo(request.getUserMessage().trim().replaceAll("\\s+", " "), 80);
                if (StringUtils.isNotBlank(initialTitle)
                        && conversationMapper.updateInitialTitle(conversation.getConversationId(), userId, initialTitle) == 1)
                {
                    conversation.setTitle(initialTitle);
                }
            }
        }

        conversationMapper.touch(conversation.getConversationId(), userId, trimTo(request.getRoute(), 255));
        try
        {
            return callModel(conversation, request, selection, run);
        }
        catch (ServiceException e)
        {
            runService.fail(run.getRunId(), safeMessage(e));
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

        List<ApprovedTool> approvedTools = toolCoordinator.approveTools(request);
        AiModel selectedModel = configService.requireModel(selection.modelId());
        AiPromptContextAssembler.AssembledContext assembled = contextAssembler.assemble(
                conversation, run, selectedModel, selection.reasoningEffort(), request, approvedTools);

        AgentRuntimeResult response;
        try
        {
            response = runService.call(run.getRunId(), () -> agentRuntime.call(new AgentRuntimeRequest(
                    selection.modelId(), selection.reasoningEffort(), assembled.cacheKey(),
                    assembled.messages(), assembled.runtimeTools())));
        }
        catch (InterruptedException e)
        {
            Thread.interrupted();
            return runState(conversation, runService.get(run.getRunId()));
        }
        catch (Exception e)
        {
            throw new ServiceException("AI 模型调用失败：" + safeMessage(e));
        }

        if (response == null)
        {
            throw new ServiceException("AI 模型未返回有效响应");
        }
        runService.recordUsage(run.getRunId(), response.usage());
        if (!runService.runnable(run.getRunId()))
        {
            return runState(conversation, runService.get(run.getRunId()));
        }

        TurnSelection actualSelection = new TurnSelection(response.modelId(), response.modelCode(),
                selection.reasoningEffort());
        if (response.hasToolCalls())
        {
            return handleToolCall(conversation, request, actualSelection, run, approvedTools, response);
        }

        String answer = StringUtils.defaultString(response.text());
        AiMessage assistantMessage = buildMessage(conversation.getConversationId(), run.getRunId(), "ASSISTANT",
                trimTo(answer, 20000), null, null, null, actualSelection);
        if (!messageService.completeWithAssistant(assistantMessage))
        {
            return runState(conversation, runService.get(run.getRunId()));
        }
        return AiChatTurnResponse.message(conversation.getConversationId(), run.getRunId(), answer);
    }

    private AiChatTurnResponse handleToolCall(AiConversation conversation, AiChatTurnRequest request,
            TurnSelection selection, AiRun run, List<ApprovedTool> approvedTools, AgentRuntimeResult output)
    {
        AiToolCallCoordinator.PreparedToolCall prepared = toolCoordinator.prepareToolCall(conversation, request, run,
                selection.modelId(), selection.modelCode(), selection.reasoningEffort(), approvedTools, output);
        if (prepared == null)
        {
            return runState(conversation, runService.get(run.getRunId()));
        }

        AiChatTurnResponse.ToolCall call = new AiChatTurnResponse.ToolCall();
        call.setCallId(prepared.callId());
        call.setName(prepared.name());
        call.setArguments(prepared.arguments());
        call.setRiskLevel(prepared.riskLevel());
        call.setDescription(prepared.description());
        return AiChatTurnResponse.toolCall(conversation.getConversationId(), run.getRunId(), call);
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

    private AiMessage buildMessage(Long conversationId, Long runId, String role, String content, String toolCallId,
            String toolName, String toolArguments, TurnSelection selection)
    {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
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
        return message;
    }

    private AiChatTurnResponse runState(AiConversation conversation, AiRun run)
    {
        String status = run == null ? "CANCELLED" : run.getStatus();
        return AiChatTurnResponse.state(conversation.getConversationId(), run == null ? null : run.getRunId(), status);
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
