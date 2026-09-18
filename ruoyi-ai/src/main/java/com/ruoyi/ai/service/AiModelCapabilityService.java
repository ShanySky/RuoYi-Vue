package com.ruoyi.ai.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import com.ruoyi.ai.domain.AiModel;
import com.ruoyi.ai.mapper.AiModelMapper;
import com.ruoyi.ai.tool.AiFrontendToolCallback;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

@Service
public class AiModelCapabilityService
{
    private static final String ECHO_TOOL = "ai_echo_test";
    private static final String ECHO_SCHEMA = """
            {"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"additionalProperties":false}
            """;

    private final AiAgentModelFactory modelFactory;
    private final AiModelMapper modelMapper;

    public AiModelCapabilityService(AiAgentModelFactory modelFactory, AiModelMapper modelMapper)
    {
        this.modelFactory = modelFactory;
        this.modelMapper = modelMapper;
    }

    public String testToolCalling(Long modelId)
    {
        AiFrontendToolCallback callback = new AiFrontendToolCallback(ECHO_TOOL,
                "无副作用能力测试工具。收到要求时用 value=AI_OK 调用它。", ECHO_SCHEMA);
        AiAgentModelFactory.ModelRuntime runtime = modelFactory.create(modelId, List.of(callback));
        String capability = "UNSUPPORTED";
        try
        {
            ChatResponse response = runtime.chatModel().call(new Prompt(
                    "必须调用 ai_echo_test 工具，参数 value 必须为 AI_OK。不要直接用文字回答。", runtime.options()));
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null
                    && response.getResult().getOutput().hasToolCalls()
                    && response.getResult().getOutput().getToolCalls().stream().anyMatch(call -> ECHO_TOOL.equals(call.name())))
            {
                capability = "SUPPORTED";
            }
        }
        catch (Exception e)
        {
            throw new ServiceException("Tool Calling 能力测试失败：" + safeMessage(e));
        }

        AiModel model = modelFactory.create(modelId, List.of()).model();
        model.setToolCapability(capability);
        model.setUpdateBy(SecurityUtils.getUsername());
        modelMapper.updateToolCapability(model);
        return capability;
    }

    public String testReasoning(Long modelId)
    {
        List<String> supported = new ArrayList<>();
        String lastError = null;
        for (String effort : List.of("minimal", "low", "medium", "high", "xhigh"))
        {
            try
            {
                AiAgentModelFactory.ModelRuntime runtime = modelFactory.create(modelId, List.of(), effort);
                ChatResponse response = runtime.chatModel().call(new Prompt("Reply with exactly AI_OK.", runtime.options()));
                if (response != null && response.getResult() != null && response.getResult().getOutput() != null)
                {
                    supported.add(effort);
                }
            }
            catch (Exception e)
            {
                lastError = safeMessage(e);
            }
        }

        if (supported.isEmpty())
        {
            throw new ServiceException("思考能力测试未发现可用档位" + (lastError == null ? "" : "：" + lastError));
        }

        AiModel model = modelFactory.create(modelId, List.of()).model();
        model.setReasoningCapability(AiConfigService.CAPABILITY_SUPPORTED);
        model.setReasoningEfforts(String.join(",", supported));
        model.setUpdateBy(SecurityUtils.getUsername());
        modelMapper.updateReasoningCapability(model);
        return model.getReasoningEfforts();
    }

    private String safeMessage(Exception e)
    {
        String value = e.getMessage();
        if (value == null || value.isBlank())
        {
            return e.getClass().getSimpleName();
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
