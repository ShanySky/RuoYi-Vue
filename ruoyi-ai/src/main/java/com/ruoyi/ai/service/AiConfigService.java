package com.ruoyi.ai.service;

import java.util.Date;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.ai.domain.AiModel;
import com.ruoyi.ai.domain.AiProvider;
import com.ruoyi.ai.dto.AiProviderSaveRequest;
import com.ruoyi.ai.dto.AiProviderView;
import com.ruoyi.ai.mapper.AiModelMapper;
import com.ruoyi.ai.mapper.AiProviderMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;

@Service
public class AiConfigService
{
    public static final String PROVIDER_TYPE = "OPENAI_COMPATIBLE";
    public static final String CAPABILITY_UNKNOWN = "UNKNOWN";

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final AiCryptoService cryptoService;
    private final AiOpenAiClient openAiClient;

    public AiConfigService(AiProviderMapper providerMapper, AiModelMapper modelMapper, AiCryptoService cryptoService,
            AiOpenAiClient openAiClient)
    {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.cryptoService = cryptoService;
        this.openAiClient = openAiClient;
    }

    public AiProviderView getProviderView()
    {
        AiProvider provider = providerMapper.selectFirst();
        AiProviderView view = new AiProviderView();
        if (provider == null)
        {
            view.setName("默认 AI 服务");
            view.setProviderType(PROVIDER_TYPE);
            view.setEnabled(false);
            view.setTimeoutSeconds(30);
            return view;
        }
        view.setProviderId(provider.getProviderId());
        view.setName(provider.getName());
        view.setProviderType(provider.getProviderType());
        view.setBaseUrl(provider.getBaseUrl());
        view.setEnabled("0".equals(provider.getEnabled()));
        view.setTimeoutSeconds(provider.getTimeoutSeconds());
        view.setHasToken(StringUtils.isNotEmpty(provider.getTokenCipher()));
        view.setTokenMask(view.isHasToken() ? "••••••••" : null);
        return view;
    }

    @Transactional
    public AiProviderView saveProvider(AiProviderSaveRequest request)
    {
        AiProvider existing = providerMapper.selectFirst();
        AiProvider provider = existing == null ? new AiProvider() : existing;
        String token = StringUtils.trim(request.getToken());
        if (existing == null && StringUtils.isEmpty(token))
        {
            throw new ServiceException("首次配置必须填写 Token");
        }
        provider.setName(StringUtils.isEmpty(request.getName()) ? "默认 AI 服务" : request.getName().trim());
        provider.setProviderType(PROVIDER_TYPE);
        provider.setBaseUrl(openAiClient.validateBaseUrl(request.getBaseUrl()));
        if (StringUtils.isNotEmpty(token))
        {
            provider.setTokenCipher(cryptoService.encrypt(token));
        }
        provider.setEnabled(Boolean.TRUE.equals(request.getEnabled()) ? "0" : "1");
        provider.setTimeoutSeconds(request.getTimeoutSeconds() == null ? 30 : request.getTimeoutSeconds());
        String username = SecurityUtils.getUsername();
        if (existing == null)
        {
            provider.setCreateBy(username);
            providerMapper.insert(provider);
        }
        else
        {
            provider.setUpdateBy(username);
            providerMapper.update(provider);
        }
        return getProviderView();
    }

    public List<String> testConnection(AiProviderSaveRequest request)
    {
        AiProvider existing = providerMapper.selectFirst();
        String baseUrl = StringUtils.isNotEmpty(request.getBaseUrl()) ? request.getBaseUrl() : existing == null ? null : existing.getBaseUrl();
        String token = StringUtils.trim(request.getToken());
        if (StringUtils.isEmpty(token) && existing != null)
        {
            token = cryptoService.decrypt(existing.getTokenCipher());
        }
        int timeout = request.getTimeoutSeconds() == null ? existing == null || existing.getTimeoutSeconds() == null ? 30 : existing.getTimeoutSeconds() : request.getTimeoutSeconds();
        return openAiClient.listModels(baseUrl, token, timeout);
    }

    @Transactional
    public List<AiModel> syncModels()
    {
        AiProvider provider = requireProvider();
        String token = cryptoService.decrypt(provider.getTokenCipher());
        List<String> codes = openAiClient.listModels(provider.getBaseUrl(), token, timeout(provider));
        String username = SecurityUtils.getUsername();
        Date now = new Date();
        for (String code : codes)
        {
            AiModel existing = modelMapper.selectByProviderAndCode(provider.getProviderId(), code);
            if (existing == null)
            {
                AiModel model = new AiModel();
                model.setProviderId(provider.getProviderId());
                model.setModelCode(code);
                model.setDisplayName(code);
                model.setEnabled("1");
                model.setDefaultModel("1");
                model.setToolCapability(CAPABILITY_UNKNOWN);
                model.setLastSyncTime(now);
                model.setCreateBy(username);
                modelMapper.insert(model);
            }
            else
            {
                existing.setDisplayName(code);
                existing.setLastSyncTime(now);
                existing.setUpdateBy(username);
                modelMapper.updateSync(existing);
            }
        }
        return modelMapper.selectByProviderId(provider.getProviderId());
    }

    public List<AiModel> listModels()
    {
        AiProvider provider = providerMapper.selectFirst();
        return provider == null ? List.of() : modelMapper.selectByProviderId(provider.getProviderId());
    }

    public List<AiModel> listEnabledModels()
    {
        AiProvider provider = providerMapper.selectFirst();
        if (provider == null || !"0".equals(provider.getEnabled())
                || StringUtils.isEmpty(provider.getBaseUrl()) || StringUtils.isEmpty(provider.getTokenCipher()))
        {
            return List.of();
        }
        return modelMapper.selectEnabled();
    }

    public void requireAgentRuntimeEnabled()
    {
        AiProvider provider = requireProvider();
        if (!"0".equals(provider.getEnabled()))
        {
            throw new ServiceException("AI 服务当前未启用");
        }
    }

    public void setModelEnabled(Long modelId, boolean enabled)
    {
        AiModel model = requireModel(modelId);
        model.setEnabled(enabled ? "0" : "1");
        model.setUpdateBy(SecurityUtils.getUsername());
        modelMapper.updateEnabled(model);
    }

    @Transactional
    public void setDefaultModel(Long modelId)
    {
        AiModel model = requireModel(modelId);
        String username = SecurityUtils.getUsername();
        modelMapper.clearDefault(model.getProviderId(), username);
        modelMapper.setDefault(modelId, username);
    }

    public String testChat(Long modelId)
    {
        AiModel model = requireModel(modelId);
        AiProvider provider = requireProvider();
        return openAiClient.simpleChat(provider.getBaseUrl(), cryptoService.decrypt(provider.getTokenCipher()),
                model.getModelCode(), timeout(provider), "Reply with exactly AI_OK");
    }

    public AiProvider requireProvider()
    {
        AiProvider provider = providerMapper.selectFirst();
        if (provider == null || StringUtils.isEmpty(provider.getBaseUrl()) || StringUtils.isEmpty(provider.getTokenCipher()))
        {
            throw new ServiceException("请先完成 AI Provider 配置");
        }
        return provider;
    }

    public AiModel requireModel(Long modelId)
    {
        AiModel model = modelMapper.selectById(modelId);
        if (model == null)
        {
            throw new ServiceException("模型不存在");
        }
        return model;
    }

    private int timeout(AiProvider provider)
    {
        return provider.getTimeoutSeconds() == null ? 30 : provider.getTimeoutSeconds();
    }
}
