package com.ruoyi.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiConversation;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.AiPendingToolCall;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.dto.AiChatTurnRequest;
import com.ruoyi.ai.dto.AiFrontendToolDefinition;
import com.ruoyi.ai.dto.AiToolResultRequest;
import com.ruoyi.ai.mapper.AiMessageMapper;
import com.ruoyi.ai.mapper.AiPendingToolCallMapper;
import com.ruoyi.ai.protocol.AiCapabilityProtocolValidator;
import com.ruoyi.ai.runtime.AgentRuntimeResult;
import com.ruoyi.ai.server.AiServerToolService;
import com.ruoyi.ai.tool.AiFrontendToolPolicy;
import com.ruoyi.ai.tool.AiFrontendToolPolicy.ApprovedTool;
import com.ruoyi.ai.tool.ToolPolicyDefinition;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

@Service
public class AiToolCallCoordinator
{
    private static final int MAX_TOOL_RESULTS_PER_TURN = 16;
    private static final int MAX_TOOL_RESULT_CHARS = 20000;

    private final AiPendingToolCallMapper pendingMapper;
    private final AiMessageMapper messageMapper;
    private final AiMessageService messageService;
    private final AiFrontendToolPolicy toolPolicy;
    private final AiRunService runService;
    private final AiPageConfigService pageConfigService;
    private final AiCapabilityProtocolValidator protocolValidator;
    private final ObjectMapper objectMapper;
    private final AiServerToolService serverTools;

    public AiToolCallCoordinator(AiPendingToolCallMapper pendingMapper, AiMessageMapper messageMapper,
            AiMessageService messageService, AiFrontendToolPolicy toolPolicy, AiRunService runService,
            AiPageConfigService pageConfigService, AiCapabilityProtocolValidator protocolValidator,
            ObjectMapper objectMapper, AiServerToolService serverTools)
    {
        this.pendingMapper = pendingMapper;
        this.messageMapper = messageMapper;
        this.messageService = messageService;
        this.toolPolicy = toolPolicy;
        this.runService = runService;
        this.pageConfigService = pageConfigService;
        this.protocolValidator = protocolValidator;
        this.objectMapper = objectMapper;
        this.serverTools = serverTools;
    }

    public void validateRequest(AiChatTurnRequest request)
    {
        protocolValidator.validateRequest(request);
    }

    public List<ApprovedTool> approveTools(AiChatTurnRequest request)
    {
        List<AiFrontendToolDefinition> offered = request.getFrontendTools() == null
                ? List.of() : request.getFrontendTools();
        if (!pageConfigService.isEnabled(request.getRoute()))
        {
            offered = offered.stream().filter(t -> t != null && isNavigationTool(t.getName())).toList();
        }
        return toolPolicy.approve(offered);
    }

    public List<ApprovedTool> approveTools(AiChatTurnRequest request, AiRun run)
    {
        List<ApprovedTool> approved = new ArrayList<>(approveTools(request));
        approved.addAll(serverTools.definitions(run));
        return approved;
    }

    @Transactional
    public PreparedToolCall prepareToolCall(AiConversation conversation, AiChatTurnRequest request,
            AiRun run, Long modelId, String modelCode, String reasoningEffort,
            List<ApprovedTool> approvedTools, AgentRuntimeResult output)
    {
        if (output.toolCalls().size() != 1
                && output.toolCalls().stream().anyMatch(call -> !serverTools.supports(call.name())))
        {
            throw new ServiceException("当前不支持一次返回多个页面工具调用");
        }
        if (messageMapper.countToolResultsInCurrentTurn(conversation.getConversationId()) >= MAX_TOOL_RESULTS_PER_TURN)
        {
            throw new ServiceException("本轮工具调用次数已超过限制");
        }

        AgentRuntimeResult.ToolCall modelCall = output.toolCalls().get(0);
        if (serverTools.supports(modelCall.name())
                && (modelCall.arguments() == null || modelCall.arguments().length() > 20000))
        {
            throw new ServiceException("服务端工具参数为空或超过长度上限");
        }
        ApprovedTool approved = approvedTools.stream().filter(t -> t.name().equals(modelCall.name())).findFirst()
                .orElseThrow(() -> new ServiceException("模型请求了当前页面不可用的工具：" + modelCall.name()));
        if (isNavigationTool(modelCall.name()))
        {
            validateNavigationTarget(modelCall.arguments());
        }

        String runtimeCallId = "rtc_" + UUID.randomUUID().toString().replace("-", "");
        String callText = output.toolCalls().size() > 1
                ? "本轮仅受理首个服务端动作，其他请求尚未执行；须根据首个结果重新决定后续动作。"
                : output.text();
        AiMessage toolCallMessage = buildMessage(conversation.getConversationId(), run.getRunId(), "ASSISTANT",
                trimTo(callText, 12000), runtimeCallId, modelCall.name(), trimTo(modelCall.arguments(), 20000),
                modelId, modelCode, reasoningEffort);

        AiPendingToolCall pending = new AiPendingToolCall();
        pending.setCallId(runtimeCallId);
        pending.setConversationId(conversation.getConversationId());
        pending.setUserId(conversation.getUserId());
        pending.setToolName(modelCall.name());
        pending.setArgumentsJson(trimTo(modelCall.arguments(), 20000));
        pending.setRiskLevel(approved.riskLevel());
        pending.setModelId(modelId);
        pending.setModelCode(modelCode);
        pending.setReasoningEffort(reasoningEffort);
        pending.setRunId(run.getRunId());
        pending.setCapabilityProtocol(trimTo(request.getCapabilityProtocol(), 64));
        pending.setPageId(trimTo(request.getPageId(), 128));
        pending.setRoute(trimTo(request.getRoute(), 255));
        pending.setPageInstanceId(trimTo(request.getPageInstanceId(), 64));
        pending.setPageVersion(request.getPageVersion());

        try
        {
            if (!messageService.persistToolCall(toolCallMessage, pending))
            {
                return null;
            }
            if (serverTools.supports(modelCall.name())) serverTools.prepare(pending);
        }
        catch (Exception e)
        {
            runService.fail(run.getRunId(), "TOOL_STATE_SAVE_FAILED");
            throw new ServiceException("AI 页面工具调用状态保存失败，请重试");
        }
        return new PreparedToolCall(runtimeCallId, modelCall.name(), modelCall.arguments(),
                approved.riskLevel(), approved.description());
    }

    public AiPendingToolCall acceptToolResult(AiConversation conversation, Long userId, AiChatTurnRequest request)
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

        if (serverTools.supports(pending.getToolName()))
        {
            String trusted = toJson(serverTools.trustedResult(conversation.getConversationId(), result.getCallId()));
            AiMessage message = buildMessage(conversation.getConversationId(), pending.getRunId(), "TOOL",
                    trusted, pending.getCallId(), pending.getToolName(), null,
                    pending.getModelId(), pending.getModelCode(), pending.getReasoningEffort());
            if (!messageService.resolveToolResult(message, pending.getPendingId()))
                throw new ServiceException("当前运行已停止或工具结果已提交");
            return pending;
        }

        boolean navigation = isNavigationTool(pending.getToolName());
        protocolValidator.validateContinuation(request, pending, !navigation);
        if (!navigation && !pageConfigService.isEnabled(request.getRoute()))
        {
            throw new ServiceException("当前页面已被管理员停用 AI 页面能力");
        }

        ToolPolicyDefinition policy;
        try
        {
            policy = toolPolicy.requirePolicy(pending.getToolName());
        }
        catch (IllegalArgumentException e)
        {
            throw new ServiceException("页面工具不在服务端允许列表中");
        }

        AiFrontendToolDefinition def = new AiFrontendToolDefinition();
        def.setName(pending.getToolName());
        def.setDescription(policy.defaultDescription());
        def.setInputSchema(Map.of("type", "object", "properties", Map.of()));
        if (toolPolicy.approve(List.of(def)).isEmpty())
        {
            throw new ServiceException("当前用户已无权完成此页面工具调用");
        }

        Map<String, Object> pageRuntime = new LinkedHashMap<>();
        pageRuntime.put("capabilityProtocol", request.getCapabilityProtocol());
        pageRuntime.put("pageId", request.getPageId());
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

        AiMessage toolResultMessage = buildMessage(conversation.getConversationId(), pending.getRunId(), "TOOL",
                resultJson, pending.getCallId(), pending.getToolName(), null,
                pending.getModelId(), pending.getModelCode(), pending.getReasoningEffort());
        if (!messageService.resolveToolResult(toolResultMessage, pending.getPendingId()))
        {
            throw new ServiceException("当前 Run 已停止或被新指令替代");
        }
        return pending;
    }

    private void validateNavigationTarget(String arguments)
    {
        try
        {
            Map<?, ?> values = objectMapper.readValue(StringUtils.defaultString(arguments, "{}"), Map.class);
            Object path = values.get("path");
            if (path == null || !pageConfigService.isEnabled(String.valueOf(path)))
            {
                throw new ServiceException("目标页面未启用 AI 页面能力");
            }
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServiceException("导航工具参数无效");
        }
    }

    private AiMessage buildMessage(Long conversationId, Long runId, String role, String content, String toolCallId,
            String toolName, String toolArguments, Long modelId, String modelCode, String reasoningEffort)
    {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        message.setToolArguments(toolArguments);
        message.setRunId(runId);
        message.setModelId(modelId);
        message.setModelCode(modelCode);
        message.setReasoningEffort(reasoningEffort);
        return message;
    }

    private boolean isNavigationTool(String name)
    {
        return "app_navigate".equals(name);
    }

    private String toJson(Object value)
    {
        try
        {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e)
        {
            throw new ServiceException("AI Tool Result JSON 序列化失败");
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

    public record PreparedToolCall(String callId, String name, String arguments, String riskLevel, String description)
    {
    }
}
