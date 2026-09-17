package com.ruoyi.ai.dto;

import jakarta.validation.constraints.NotNull;

public class AiModelStatusRequest
{
    @NotNull
    private Boolean enabled;

    public Boolean getEnabled()
    {
        return enabled;
    }

    public void setEnabled(Boolean enabled)
    {
        this.enabled = enabled;
    }
}
