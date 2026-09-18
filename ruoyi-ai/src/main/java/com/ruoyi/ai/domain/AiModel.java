package com.ruoyi.ai.domain;

import java.util.Date;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * Model discovered from an AI Provider.
 */
public class AiModel extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    private Long modelId;
    private Long providerId;
    private String modelCode;
    private String displayName;
    private String selected;
    private String enabled;
    private String defaultModel;
    private String toolCapability;
    private String reasoningCapability;
    private String reasoningEfforts;
    private String defaultReasoningEffort;
    private Date lastSyncTime;

    public Long getModelId()
    {
        return modelId;
    }

    public void setModelId(Long modelId)
    {
        this.modelId = modelId;
    }

    public Long getProviderId()
    {
        return providerId;
    }

    public void setProviderId(Long providerId)
    {
        this.providerId = providerId;
    }

    public String getModelCode()
    {
        return modelCode;
    }

    public void setModelCode(String modelCode)
    {
        this.modelCode = modelCode;
    }

    public String getDisplayName()
    {
        return displayName;
    }

    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    public String getSelected()
    {
        return selected;
    }

    public void setSelected(String selected)
    {
        this.selected = selected;
    }

    public String getEnabled()
    {
        return enabled;
    }

    public void setEnabled(String enabled)
    {
        this.enabled = enabled;
    }

    public String getDefaultModel()
    {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel)
    {
        this.defaultModel = defaultModel;
    }

    public String getToolCapability()
    {
        return toolCapability;
    }

    public void setToolCapability(String toolCapability)
    {
        this.toolCapability = toolCapability;
    }

    public String getReasoningCapability()
    {
        return reasoningCapability;
    }

    public void setReasoningCapability(String reasoningCapability)
    {
        this.reasoningCapability = reasoningCapability;
    }

    public String getReasoningEfforts()
    {
        return reasoningEfforts;
    }

    public void setReasoningEfforts(String reasoningEfforts)
    {
        this.reasoningEfforts = reasoningEfforts;
    }

    public String getDefaultReasoningEffort()
    {
        return defaultReasoningEffort;
    }

    public void setDefaultReasoningEffort(String defaultReasoningEffort)
    {
        this.defaultReasoningEffort = defaultReasoningEffort;
    }

    public Date getLastSyncTime()
    {
        return lastSyncTime;
    }

    public void setLastSyncTime(Date lastSyncTime)
    {
        this.lastSyncTime = lastSyncTime;
    }
}
