package com.ruoyi.ai.dto;

public class AiConversationCreateRequest
{
    private Long modelId;
    private String reasoningEffort;
    private String route;

    public Long getModelId()
    {
        return modelId;
    }

    public void setModelId(Long modelId)
    {
        this.modelId = modelId;
    }

    public String getReasoningEffort()
    {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort)
    {
        this.reasoningEffort = reasoningEffort;
    }

    public String getRoute()
    {
        return route;
    }

    public void setRoute(String route)
    {
        this.route = route;
    }
}
