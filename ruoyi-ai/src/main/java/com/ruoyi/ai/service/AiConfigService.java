package com.ruoyi.ai.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    public static final String CAPABILITY_SUPPORTED = "SUPPORTED";
    public static final String CAPABILITY_UNSUPPORTED = "UNSUPPORTED";
    private static final Set<String> REASONING_EFFORTS = Set.of("minimal", "low", "medium", "high", "xhigh");

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

    /**
     * Test whether the current form can load the Provider's /models catalog.
     * This does not persist any model.
     */
    public List<String> testConnection(AiProviderSaveRequest request)
    {
        AiProvider existing = providerMapper.selectFirst();
        String baseUrl = StringUtils.isNotEmpty(request.getBaseUrl()) ? request.getBaseUrl()
                : existing == null ? null : existing.getBaseUrl();
        String token = StringUtils.trim(request.getToken());
        if (StringUtils.isEmpty(token) && existing != null)
        {
            token = cryptoService.decrypt(existing.getTokenCipher());
        }
        int timeout = request.getTimeoutSeconds() == null
                ? existing == null || existing.getTimeoutSeconds() == null ? 30 : existing.getTimeoutSeconds()
                : request.getTimeoutSeconds();
        return openAiClient.listModels(baseUrl, token, timeout);
    }

    /**
     * Refresh the remote catalog from the saved Provider without persisting it.
     */
    public List<String> discoverModels()
    {
        AiProvider provider = requireProvider();
        return openAiClient.listModels(provider.getBaseUrl(), cryptoService.decrypt(provider.getTokenCipher()),
                timeout(provider));
    }

    @Transactional
    public List<AiModel> addModels(List<String> requestedCodes)
    {
        AiProvider provider = requireProvider();
        Set<String> remoteCodes = new HashSet<>(discoverModels());
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (requestedCodes != null)
        {
            for (String raw : requestedCodes)
            {
                String code = StringUtils.trim(raw);
                if (StringUtils.isEmpty(code))
                {
                    continue;
                }
                if (code.length() > 191)
                {
                    throw new ServiceException("模型标识过长：" + code.substring(0, 80));
                }
                codes.add(code);
            }
        }
        if (codes.isEmpty())
        {
            throw new ServiceException("请至少选择一个模型");
        }
        for (String code : codes)
        {
            if (!remoteCodes.contains(code))
            {
                throw new ServiceException("远端模型目录中不存在：" + code);
            }
        }

        String username = SecurityUtils.getUsername();
        Date now = new Date();
        boolean hasDefault = modelMapper.selectDefaultEnabled() != null;
        List<AiModel> added = new ArrayList<>();

        for (String code : codes)
        {
            AiModel existing = modelMapper.selectByProviderAndCode(provider.getProviderId(), code);
            if (existing == null)
            {
                AiModel model = new AiModel();
                model.setProviderId(provider.getProviderId());
                model.setModelCode(code);
                model.setDisplayName(code);
                model.setSelected("0");
                model.setEnabled("0");
                model.setDefaultModel(hasDefault ? "1" : "0");
                model.setToolCapability(CAPABILITY_UNKNOWN);
                model.setReasoningCapability(CAPABILITY_UNKNOWN);
                model.setReasoningEfforts(null);
                model.setDefaultReasoningEffort(null);
                model.setLastSyncTime(now);
                model.setCreateBy(username);
                modelMapper.insert(model);
                added.add(model);
                hasDefault = true;
                continue;
            }

            if ("0".equals(existing.getSelected()))
            {
                added.add(existing);
                continue;
            }

            existing.setSelected("0");
            existing.setEnabled("0");
            existing.setDisplayName(code);
            existing.setLastSyncTime(now);
            existing.setUpdateBy(username);
            modelMapper.restoreSelected(existing);
            if (!hasDefault)
            {
                modelMapper.clearDefault(provider.getProviderId(), username);
                modelMapper.setDefault(existing.getModelId(), username);
                hasDefault = true;
            }
            added.add(modelMapper.selectById(existing.getModelId()));
        }
        return added;
    }

    @Transactional
    public void removeModel(Long modelId)
    {
        AiModel model = requireSystemModel(modelId);
        boolean wasDefault = "0".equals(model.getDefaultModel());
        model.setUpdateBy(SecurityUtils.getUsername());
        modelMapper.archiveSelected(model);
        if (wasDefault)
        {
            AiModel next = modelMapper.selectFirstEnabledSelected();
            if (next != null)
            {
                modelMapper.setDefault(next.getModelId(), SecurityUtils.getUsername());
            }
        }
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

    public AiModel getDefaultEnabledModel()
    {
        return modelMapper.selectDefaultEnabled();
    }

    @Transactional
    public void setModelEnabled(Long modelId, boolean enabled)
    {
        AiModel model = requireSystemModel(modelId);
        boolean wasDefault = "0".equals(model.getDefaultModel());
        model.setEnabled(enabled ? "0" : "1");
        model.setUpdateBy(SecurityUtils.getUsername());
        modelMapper.updateEnabled(model);

        if (!enabled && wasDefault)
        {
            modelMapper.clearDefault(model.getProviderId(), SecurityUtils.getUsername());
            AiModel next = modelMapper.selectFirstEnabledSelected();
            if (next != null)
            {
                modelMapper.setDefault(next.getModelId(), SecurityUtils.getUsername());
            }
        }
        else if (enabled && modelMapper.selectDefaultEnabled() == null)
        {
            modelMapper.clearDefault(model.getProviderId(), SecurityUtils.getUsername());
            modelMapper.setDefault(model.getModelId(), SecurityUtils.getUsername());
        }
    }

    @Transactional
    public void setDefaultModel(Long modelId)
    {
        AiModel model = requireSystemModel(modelId);
        String username = SecurityUtils.getUsername();
        modelMapper.clearDefault(model.getProviderId(), username);
        modelMapper.setDefault(modelId, username);
    }

    public void setDefaultReasoningEffort(Long modelId, String reasoningEffort)
    {
        AiModel model = requireSystemModel(modelId);
        String normalized = normalizeReasoningEffort(reasoningEffort);
        if (normalized != null && !supportsReasoningEffort(model, normalized))
        {
            throw new ServiceException("该模型尚未确认支持思考档位 " + normalized + "，请先完成能力检测或使用 Provider 默认");
        }
        model.setDefaultReasoningEffort(normalized);
        model.setUpdateBy(SecurityUtils.getUsername());
        modelMapper.updateDefaultReasoningEffort(model);
    }

    public String resolveReasoningEffort(AiModel model, String requested)
    {
        String normalized = normalizeReasoningEffort(requested);
        if (normalized == null)
        {
            normalized = normalizeReasoningEffort(model.getDefaultReasoningEffort());
        }
        if (normalized != null && !supportsReasoningEffort(model, normalized))
        {
            throw new ServiceException("所选模型尚未确认支持思考档位 " + normalized + "，请使用 Provider 默认或先完成能力检测");
        }
        return normalized;
    }

    private boolean supportsReasoningEffort(AiModel model, String effort)
    {
        if (!CAPABILITY_SUPPORTED.equals(model.getReasoningCapability()) || StringUtils.isEmpty(model.getReasoningEfforts()))
        {
            return false;
        }
        for (String item : model.getReasoningEfforts().split(","))
        {
            if (effort.equals(item.trim()))
            {
                return true;
            }
        }
        return false;
    }

    public String normalizeReasoningEffort(String reasoningEffort)
    {
        String value = StringUtils.trim(reasoningEffort);
        if (StringUtils.isEmpty(value) || "default".equalsIgnoreCase(value) || "auto".equalsIgnoreCase(value))
        {
            return null;
        }
        value = value.toLowerCase();
        if (!REASONING_EFFORTS.contains(value))
        {
            throw new ServiceException("不支持的思考档位：" + value);
        }
        return value;
    }

    public String testChat(Long modelId)
    {
        AiModel model = requireSystemModel(modelId);
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

    /**
     * Internal lookup. Pending Tool Result resume may legitimately use a model that was
     * removed from the current system-model list after the tool call was created.
     */
    public AiModel requireModel(Long modelId)
    {
        AiModel model = modelMapper.selectById(modelId);
        if (model == null)
        {
            throw new ServiceException("模型不存在");
        }
        return model;
    }

    public AiModel requireSystemModel(Long modelId)
    {
        AiModel model = requireModel(modelId);
        if (!"0".equals(model.getSelected()))
        {
            throw new ServiceException("模型未加入当前系统模型列表");
        }
        return model;
    }

    public AiModel requireEnabledSystemModel(Long modelId)
    {
        AiModel model = requireSystemModel(modelId);
        if (!"0".equals(model.getEnabled()))
        {
            throw new ServiceException("模型当前未启用");
        }
        return model;
    }

    private int timeout(AiProvider provider)
    {
        return provider.getTimeoutSeconds() == null ? 30 : provider.getTimeoutSeconds();
    }
}
