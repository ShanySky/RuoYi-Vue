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
                .maxCompletionTokens(AiTokenBudget.outputLimit(model))
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

    public boolean isPromptCacheUnsupported(Throwable error)
    {
        StringBuilder messages = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 8)
        {
            if (current.getMessage() != null)
            {
                messages.append(' ').append(current.getMessage().toLowerCase(java.util.Locale.ROOT));
            }
            current = current.getCause();
        }
        String text = messages.toString();
        boolean namesCacheParameter = text.contains("prompt_cache_key")
                || text.contains("prompt cache key")
                || text.contains("prompt-cache-key");
        boolean saysUnsupported = text.contains("unsupported")
                || text.contains("unknown")
                || text.contains("unrecognized")
                || text.contains("not permitted")
                || text.contains("not allowed")
                || text.contains("extra_forbidden")
                || text.contains("invalid parameter")
                || text.contains("unexpected field");
        return namesCacheParameter && saysUnsupported;
    }

    public record ModelRuntime(OpenAiChatModel chatModel, OpenAiChatOptions options, AiModel model)
    {
    }
}
