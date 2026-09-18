package com.ruoyi.ai.service;

import java.time.Duration;
import java.util.List;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import com.ruoyi.ai.domain.AiModel;
import com.ruoyi.ai.domain.AiProvider;

@Service
public class AiAgentModelFactory
{
    private final AiConfigService configService;
    private final AiCryptoService cryptoService;

    public AiAgentModelFactory(AiConfigService configService, AiCryptoService cryptoService)
    {
        this.configService = configService;
        this.cryptoService = cryptoService;
    }

    public ModelRuntime create(Long modelId, List<ToolCallback> tools)
    {
        return create(modelId, tools, null, null);
    }

    public ModelRuntime create(Long modelId, List<ToolCallback> tools, String reasoningEffort)
    {
        return create(modelId, tools, reasoningEffort, null);
    }

    public ModelRuntime create(Long modelId, List<ToolCallback> tools, String reasoningEffort, String promptCacheKey)
    {
        AiModel model = configService.requireModel(modelId);
        if (!"0".equals(model.getEnabled()))
        {
            throw new com.ruoyi.common.exception.ServiceException("所选模型未启用");
        }
        AiProvider provider = configService.requireProvider();
        if (!provider.getProviderId().equals(model.getProviderId()))
        {
            throw new com.ruoyi.common.exception.ServiceException("模型与当前 Provider 不匹配");
        }
        int timeout = provider.getTimeoutSeconds() == null ? 30 : provider.getTimeoutSeconds();
        var builder = OpenAiChatOptions.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(cryptoService.decrypt(provider.getTokenCipher()))
                .model(model.getModelCode())
                .timeout(Duration.ofSeconds(timeout))
                .maxRetries(0)
                .parallelToolCalls(false)
                .toolCallbacks(tools);
        if (reasoningEffort != null && !reasoningEffort.isBlank())
        {
            builder.reasoningEffort(reasoningEffort);
        }
        if (promptCacheKey != null && !promptCacheKey.isBlank())
        {
            builder.promptCacheKey(promptCacheKey);
        }
        OpenAiChatOptions options = builder.build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(options).build();
        return new ModelRuntime(chatModel, options, model);
    }

    public record ModelRuntime(OpenAiChatModel chatModel, OpenAiChatOptions options, AiModel model)
    {
    }
}
