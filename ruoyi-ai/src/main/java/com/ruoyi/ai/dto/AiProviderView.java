package com.ruoyi.ai.dto;

public class AiProviderView
{
    private Long providerId;
    private String name;
    private String providerType;
    private String baseUrl;
    private boolean enabled;
    private Integer timeoutSeconds;
    private boolean hasToken;
    private String tokenMask;

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

    public boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(boolean enabled)
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

    public boolean isHasToken()
    {
        return hasToken;
    }

    public void setHasToken(boolean hasToken)
    {
        this.hasToken = hasToken;
    }

    public String getTokenMask()
    {
        return tokenMask;
    }

    public void setTokenMask(String tokenMask)
    {
        this.tokenMask = tokenMask;
    }
}
