package com.ruoyi.ai.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * AI Provider configuration.
 */
public class AiProvider extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    private Long providerId;
    private String name;
    private String providerType;
    private String baseUrl;

    @JsonIgnore
    private String tokenCipher;

    private String enabled;
    private Integer timeoutSeconds;

    public Long getProviderId()
    {
        return providerId;
    }

    public void setProviderId(Long providerId)
    {
        this.providerId = providerId;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public String getProviderType()
    {
        return providerType;
    }

    public void setProviderType(String providerType)
    {
        this.providerType = providerType;
    }

    public String getBaseUrl()
    {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    public String getTokenCipher()
    {
        return tokenCipher;
    }

    public void setTokenCipher(String tokenCipher)
    {
        this.tokenCipher = tokenCipher;
    }

    public String getEnabled()
    {
        return enabled;
    }

    public void setEnabled(String enabled)
    {
        this.enabled = enabled;
    }

    public Integer getTimeoutSeconds()
    {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(Integer timeoutSeconds)
    {
        this.timeoutSeconds = timeoutSeconds;
    }
}
