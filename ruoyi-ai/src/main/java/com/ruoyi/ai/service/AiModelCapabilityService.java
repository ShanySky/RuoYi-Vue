package com.ruoyi.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        // First prove that the model itself is callable without any reasoning option.
        // If this fails, UNKNOWN is more honest than classifying the model as unsupported.
        try
        {
            AiAgentModelFactory.ModelRuntime base = modelFactory.create(modelId, List.of());
            ChatResponse response = base.chatModel().call(new Prompt("Reply with exactly AI_OK.", base.options()));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null)
            {
                throw new ServiceException("模型基础调用未返回有效响应");
            }
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServiceException("思考能力检测前的模型基础调用失败：" + safeMessage(e));
        }

        List<String> supported = new ArrayList<>();
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
            catch (Exception ignored)
            {
                // Unsupported reasoning values are expected for many compatible providers.
            }
        }

        AiModel model = modelFactory.create(modelId, List.of()).model();
        if (supported.isEmpty())
        {
            model.setReasoningCapability(AiConfigService.CAPABILITY_UNSUPPORTED);
            model.setReasoningEfforts(null);
        }
        else
        {
            model.setReasoningCapability(AiConfigService.CAPABILITY_SUPPORTED);
            model.setReasoningEfforts(String.join(",", supported));
        }
        model.setUpdateBy(SecurityUtils.getUsername());
        modelMapper.updateReasoningCapability(model);
        return supported.isEmpty() ? AiConfigService.CAPABILITY_UNSUPPORTED : model.getReasoningEfforts();
    }

    public Map<String, Object> detectCapabilities(Long modelId)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        String toolError = null;
        String reasoningError = null;
        try
        {
            result.put("toolCapability", testToolCalling(modelId));
        }
        catch (Exception e)
        {
            result.put("toolCapability", AiConfigService.CAPABILITY_UNKNOWN);
            toolError = safeMessage(e);
        }

        try
        {
            String reasoning = testReasoning(modelId);
            AiModel model = modelFactory.create(modelId, List.of()).model();
            result.put("reasoningCapability", model.getReasoningCapability());
            result.put("reasoningEfforts", model.getReasoningEfforts());
            result.put("reasoningResult", reasoning);
        }
        catch (Exception e)
        {
            result.put("reasoningCapability", AiConfigService.CAPABILITY_UNKNOWN);
            result.put("reasoningEfforts", null);
            reasoningError = safeMessage(e);
        }

        if (toolError != null)
        {
            result.put("toolError", toolError);
        }
        if (reasoningError != null)
        {
            result.put("reasoningError", reasoningError);
        }
        return result;
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
