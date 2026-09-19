package com.ruoyi.ai.domain;
import java.util.Date;
public class AiPendingToolCall {
 private Long pendingId,conversationId,userId,modelId,runId,pageVersion; private String callId,toolName,argumentsJson,riskLevel,modelCode,reasoningEffort,capabilityProtocol,pageId,route,pageInstanceId,status; private Date createTime,expireTime,resolvedTime;
 public Long getPendingId(){return pendingId;} public void setPendingId(Long v){pendingId=v;} public String getCallId(){return callId;} public void setCallId(String v){callId=v;}
 public Long getConversationId(){return conversationId;} public void setConversationId(Long v){conversationId=v;} public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
 public String getToolName(){return toolName;} public void setToolName(String v){toolName=v;} public String getArgumentsJson(){return argumentsJson;} public void setArgumentsJson(String v){argumentsJson=v;}
 public String getRiskLevel(){return riskLevel;} public void setRiskLevel(String v){riskLevel=v;} public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;}
 public String getModelCode(){return modelCode;} public void setModelCode(String v){modelCode=v;} public String getReasoningEffort(){return reasoningEffort;} public void setReasoningEffort(String v){reasoningEffort=v;}
 public Long getRunId(){return runId;} public void setRunId(Long v){runId=v;} public String getCapabilityProtocol(){return capabilityProtocol;} public void setCapabilityProtocol(String v){capabilityProtocol=v;} public String getPageId(){return pageId;} public void setPageId(String v){pageId=v;} public String getRoute(){return route;} public void setRoute(String v){route=v;}
 public String getPageInstanceId(){return pageInstanceId;} public void setPageInstanceId(String v){pageInstanceId=v;} public Long getPageVersion(){return pageVersion;} public void setPageVersion(Long v){pageVersion=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;} public Date getCreateTime(){return createTime;} public void setCreateTime(Date v){createTime=v;}
 public Date getExpireTime(){return expireTime;} public void setExpireTime(Date v){expireTime=v;} public Date getResolvedTime(){return resolvedTime;} public void setResolvedTime(Date v){resolvedTime=v;}
}