package com.ruoyi.ai.domain;

import java.util.Date;

public class AiPendingToolCall
{
    private Long pendingId;
    private String callId;
    private Long conversationId;
    private Long userId;
    private String toolName;
    private String argumentsJson;
    private String riskLevel;
    private Long modelId;
    private String modelCode;
    private String reasoningEffort;
    private String status;
    private Date createTime;
    private Date expireTime;
    private Date resolvedTime;

    public Long getPendingId() { return pendingId; }
    public void setPendingId(Long pendingId) { this.pendingId = pendingId; }
    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArgumentsJson() { return argumentsJson; }
    public void setArgumentsJson(String argumentsJson) { this.argumentsJson = argumentsJson; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getExpireTime() { return expireTime; }
    public void setExpireTime(Date expireTime) { this.expireTime = expireTime; }
    public Date getResolvedTime() { return resolvedTime; }
    public void setResolvedTime(Date resolvedTime) { this.resolvedTime = resolvedTime; }
}
