package com.ruoyi.ai.runtime;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import com.ruoyi.ai.service.AiAgentModelFactory;
import com.ruoyi.ai.service.AiTokenBudget;
import com.ruoyi.ai.tool.AiFrontendToolCallback;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

@Service
public class SpringAiAgentRuntime implements AgentRuntime
{
    private final AiAgentModelFactory modelFactory;

    public SpringAiAgentRuntime(AiAgentModelFactory modelFactory)
    {
        this.modelFactory = modelFactory;
    }

    @Override
    public AgentRuntimeResult call(AgentRuntimeRequest request) throws Exception
    {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (AgentRuntimeTool tool : request.tools())
        {
            callbacks.add(new AiFrontendToolCallback(tool.name(), tool.description(), tool.inputSchemaJson()));
        }

        AiAgentModelFactory.ModelRuntime runtime = modelFactory.create(request.modelId(), callbacks,
                request.reasoningEffort(), request.promptCacheKey());
        AiTokenBudget.requireFits(runtime.model(), request);
        try
        {
            return invoke(runtime, request.messages());
        }
        catch (Exception error)
        {
            if (StringUtils.isBlank(request.promptCacheKey()) || !modelFactory.isPromptCacheUnsupported(error))
            {
                throw error;
            }
            AiAgentModelFactory.ModelRuntime fallback = modelFactory.create(request.modelId(), callbacks,
                    request.reasoningEffort(), null);
            return invoke(fallback, request.messages());
        }
    }

    private AgentRuntimeResult invoke(AiAgentModelFactory.ModelRuntime runtime, List<AgentRuntimeMessage> messages)
    {
        ChatResponse response = runtime.chatModel().call(new Prompt(toSpringMessages(messages), runtime.options()));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
        {
            throw new ServiceException("AI 模型未返回有效响应");
        }

        AssistantMessage output = response.getResult().getOutput();
        List<AgentRuntimeResult.ToolCall> toolCalls = output.getToolCalls().stream()
                .map(call -> new AgentRuntimeResult.ToolCall(call.id(), call.name(), call.arguments()))
                .toList();
        return new AgentRuntimeResult(runtime.model().getModelId(), runtime.model().getModelCode(),
                StringUtils.defaultString(output.getText()), toolCalls, usage(response), null);
    }

    private List<Message> toSpringMessages(List<AgentRuntimeMessage> messages)
    {
        List<Message> result = new ArrayList<>();
        for (AgentRuntimeMessage message : messages)
        {
            switch (message.role())
            {
                case SYSTEM -> result.add(new SystemMessage(StringUtils.defaultString(message.content())));
                case USER -> result.add(new UserMessage(StringUtils.defaultString(message.content())));
                case ASSISTANT -> {
                    if (message.toolCalls().isEmpty())
                    {
                        result.add(new AssistantMessage(StringUtils.defaultString(message.content())));
                    }
                    else
                    {
                        List<AssistantMessage.ToolCall> calls = message.toolCalls().stream()
                                .map(call -> new AssistantMessage.ToolCall(call.id(), "function", call.name(),
                                        StringUtils.defaultString(call.arguments(), "{}")))
                                .toList();
                        result.add(AssistantMessage.builder().content(message.content()).toolCalls(calls).build());
                    }
                }
                case TOOL -> {
                    ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                            message.toolCallId(), message.toolName(), StringUtils.defaultString(message.content(), "{}"));
                    result.add(ToolResponseMessage.builder().responses(List.of(response)).build());
                }
            }
        }
        return result;
    }

    private AgentRuntimeUsage usage(ChatResponse response)
    {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null)
        {
            return AgentRuntimeUsage.empty();
        }
        Usage usage = response.getMetadata().getUsage();
        return new AgentRuntimeUsage(value(usage.getPromptTokens()), value(usage.getCacheReadInputTokens()),
                value(usage.getCacheWriteInputTokens()), value(usage.getTotalTokens()));
    }

    private long value(Number value)
    {
        return value == null ? 0 : value.longValue();
    }
}
