package com.ruoyi.ai.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiConversation;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.AiPendingToolCall;
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
    private static final int MAX_TOOL_RESULTS_PER_TURN = 8;
    private static final int MAX_PAGE_CONTEXT_CHARS = 20000;
    private static final int MAX_TOOL_RESULT_CHARS = 20000;

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final AiPendingToolCallMapper pendingMapper;
    private final AiFrontendToolPolicy toolPolicy;
    private final AiAgentModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    public AiAgentLoopService(AiConversationMapper conversationMapper, AiMessageMapper messageMapper,
            AiPendingToolCallMapper pendingMapper, AiFrontendToolPolicy toolPolicy, AiAgentModelFactory modelFactory,
            ObjectMapper objectMapper)
    {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.pendingMapper = pendingMapper;
        this.toolPolicy = toolPolicy;
        this.modelFactory = modelFactory;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiChatTurnResponse turn(AiChatTurnRequest request)
    {
        validateTurnRequest(request);
        Long userId = SecurityUtils.getUserId();
        AiConversation conversation = resolveConversation(request, userId);

        if (request.getToolResult() != null)
        {
            acceptToolResult(conversation, userId, request.getToolResult());
        }
        else
        {
            insertMessage(conversation.getConversationId(), "USER", trimTo(request.getUserMessage(), 12000), null, null, null);
        }

        conversationMapper.touch(conversation.getConversationId(), userId, trimTo(request.getRoute(), 255));
        return callModel(conversation, request);
    }

    private AiChatTurnResponse callModel(AiConversation conversation, AiChatTurnRequest request)
    {
        List<ApprovedTool> approvedTools = toolPolicy.approve(request.getFrontendTools());
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ApprovedTool tool : approvedTools)
        {
            callbacks.add(new AiFrontendToolCallback(tool.name(), tool.description(), toJson(tool.inputSchema())));
        }

        AiAgentModelFactory.ModelRuntime runtime = modelFactory.create(conversation.getModelId(), callbacks);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(buildSystemPrompt(request, approvedTools)));
        messages.addAll(toSpringMessages(messageMapper.selectByConversationId(conversation.getConversationId())));

        ChatResponse response;
        try
        {
            response = runtime.chatModel().call(new Prompt(messages, runtime.options()));
        }
        catch (Exception e)
        {
            throw new ServiceException("AI 模型调用失败：" + safeMessage(e));
        }
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
        {
            throw new ServiceException("AI 模型未返回有效响应");
        }
        AssistantMessage output = response.getResult().getOutput();
        if (output.hasToolCalls())
        {
            if (output.getToolCalls().size() != 1)
            {
                throw new ServiceException("第一阶段不支持一次返回多个页面工具调用");
            }
            if (messageMapper.countToolResultsInCurrentTurn(conversation.getConversationId()) >= MAX_TOOL_RESULTS_PER_TURN)
            {
                throw new ServiceException("本轮工具调用次数已超过限制");
            }
            AssistantMessage.ToolCall modelCall = output.getToolCalls().get(0);
            ApprovedTool approved = approvedTools.stream().filter(t -> t.name().equals(modelCall.name())).findFirst()
                    .orElseThrow(() -> new ServiceException("模型请求了当前页面不可用的工具：" + modelCall.name()));
            insertMessage(conversation.getConversationId(), "ASSISTANT", trimTo(output.getText(), 12000), modelCall.id(),
                    modelCall.name(), trimTo(modelCall.arguments(), 20000));

            AiPendingToolCall pending = new AiPendingToolCall();
            pending.setCallId(modelCall.id());
            pending.setConversationId(conversation.getConversationId());
            pending.setUserId(conversation.getUserId());
            pending.setToolName(modelCall.name());
            pending.setArgumentsJson(trimTo(modelCall.arguments(), 20000));
            pending.setRiskLevel(approved.riskLevel());
            pendingMapper.insert(pending);

            AiChatTurnResponse.ToolCall call = new AiChatTurnResponse.ToolCall();
            call.setCallId(modelCall.id());
            call.setName(modelCall.name());
            call.setArguments(modelCall.arguments());
            call.setRiskLevel(approved.riskLevel());
            call.setDescription(approved.description());
            return AiChatTurnResponse.toolCall(conversation.getConversationId(), call);
        }

        String answer = StringUtils.defaultString(output.getText());
        insertMessage(conversation.getConversationId(), "ASSISTANT", trimTo(answer, 20000), null, null, null);
        return AiChatTurnResponse.message(conversation.getConversationId(), answer);
    }

    private void acceptToolResult(AiConversation conversation, Long userId, AiToolResultRequest result)
    {
        AiPendingToolCall pending = pendingMapper.selectByCall(conversation.getConversationId(), result.getCallId());
        if (pending == null || !"PENDING".equals(pending.getStatus()))
        {
            throw new ServiceException("待处理的页面工具调用不存在或已经完成");
        }
        if (!userId.equals(pending.getUserId()))
        {
            throw new ServiceException("不能提交其他用户的页面工具结果");
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
        // Permission is checked again on resume so a revoked permission cannot finish a stale call.
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
        Map<String, Object> payload = new HashMap<>();
        payload.put("success", Boolean.TRUE.equals(result.getSuccess()));
        payload.put("result", result.getResult());
        payload.put("error", trimTo(result.getError(), 2000));
        String resultJson = trimTo(toJson(payload), MAX_TOOL_RESULT_CHARS);
        insertMessage(conversation.getConversationId(), "TOOL", resultJson, pending.getCallId(), pending.getToolName(), null);
    }

    private AiConversation resolveConversation(AiChatTurnRequest request, Long userId)
    {
        if (request.getConversationId() == null)
        {
            if (request.getToolResult() != null)
            {
                throw new ServiceException("Tool Result 必须提供 conversationId");
            }
            if (request.getModelId() == null)
            {
                throw new ServiceException("新会话必须选择模型");
            }
            AiConversation conversation = new AiConversation();
            conversation.setUserId(userId);
            conversation.setModelId(request.getModelId());
            conversation.setTitle(trimTo(request.getUserMessage(), 80));
            conversation.setRoute(trimTo(request.getRoute(), 255));
            conversation.setStatus("ACTIVE");
            conversationMapper.insert(conversation);
            return conversation;
        }
        AiConversation conversation = conversationMapper.selectById(request.getConversationId());
        if (conversation == null || !userId.equals(conversation.getUserId()))
        {
            throw new ServiceException("会话不存在或无权访问");
        }
        if (!"ACTIVE".equals(conversation.getStatus()))
        {
            throw new ServiceException("会话当前不可继续");
        }
        return conversation;
    }

    private List<Message> toSpringMessages(List<AiMessage> stored)
    {
        List<Message> result = new ArrayList<>();
        for (AiMessage message : stored)
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
                    ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(message.getToolCallId(),
                            message.getToolName(), StringUtils.defaultString(message.getContent(), "{}"));
                    result.add(ToolResponseMessage.builder().responses(List.of(toolResponse)).build());
                }
                default -> throw new ServiceException("会话历史包含未知角色：" + message.getRole());
            }
        }
        return result;
    }

    private String buildSystemPrompt(AiChatTurnRequest request, List<ApprovedTool> tools)
    {
        String context = trimTo(toJson(request.getPageContext() == null ? Map.of() : request.getPageContext()), MAX_PAGE_CONTEXT_CHARS);
        String toolNames = tools.stream().map(ApprovedTool::name).toList().toString();
        return """
                你是 RuoYi 管理系统内的 AI 助手。你既可以正常对话，也可以在用户明确要求时使用当前页面提供的工具。
                规则：
                1. 只能使用请求中实际提供的工具，不能假装点击、查询、修改或保存。
                2. 页面工具执行前不要声称已经完成；只有收到对应 Tool Result 后才能确认结果。
                3. 用户要求操作当前页面时，优先使用语义化页面工具，不要描述 DOM、选择器、坐标或 Playwright。
                4. 不要虚构页面记录、ID 或工具执行结果；信息不足时根据 Page Context 或工具结果继续处理。
                5. WRITE 工具的用户确认由宿主页面负责，你不要绕过确认流程。
                6. 你的权限不超过当前登录用户。工具未提供通常表示当前页面不支持或用户没有权限。

                当前 route：%s
                当前页面工具：%s
                当前 Page Context(JSON)：%s
                """.formatted(StringUtils.defaultString(request.getRoute()), toolNames, context);
    }

    private void insertMessage(Long conversationId, String role, String content, String toolCallId, String toolName,
            String toolArguments)
    {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setSequenceNo(messageMapper.nextSequence(conversationId));
        message.setRole(role);
        message.setContent(content);
        message.setToolCallId(toolCallId);
        message.setToolName(toolName);
        message.setToolArguments(toolArguments);
        messageMapper.insert(message);
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
        return trimTo(value, 300);
    }
}
