package com.ruoyi.ai.domain;
import java.util.Date;
public class AiRun {
 private Long runId,conversationId,userId,modelId,supersededByRunId,inputTokens,cacheReadTokens,cacheWriteTokens,totalTokens;
 private String clientRunKey,modelCode,reasoningEffort,status,cancelReason;
 private Integer systemPromptVersion,compactionPromptVersion;
 private Date createTime,updateTime,endTime;
 public Long getRunId(){return runId;} public void setRunId(Long v){runId=v;} public String getClientRunKey(){return clientRunKey;} public void setClientRunKey(String v){clientRunKey=v;}
 public Long getConversationId(){return conversationId;} public void setConversationId(Long v){conversationId=v;}
 public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
 public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;}
 public String getModelCode(){return modelCode;} public void setModelCode(String v){modelCode=v;}
 public String getReasoningEffort(){return reasoningEffort;} public void setReasoningEffort(String v){reasoningEffort=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;}
 public String getCancelReason(){return cancelReason;} public void setCancelReason(String v){cancelReason=v;}
 public Long getSupersededByRunId(){return supersededByRunId;} public void setSupersededByRunId(Long v){supersededByRunId=v;}
 public Integer getSystemPromptVersion(){return systemPromptVersion;} public void setSystemPromptVersion(Integer v){systemPromptVersion=v;}
 public Integer getCompactionPromptVersion(){return compactionPromptVersion;} public void setCompactionPromptVersion(Integer v){compactionPromptVersion=v;}
 public Long getInputTokens(){return inputTokens;} public void setInputTokens(Long v){inputTokens=v;}
 public Long getCacheReadTokens(){return cacheReadTokens;} public void setCacheReadTokens(Long v){cacheReadTokens=v;}
 public Long getCacheWriteTokens(){return cacheWriteTokens;} public void setCacheWriteTokens(Long v){cacheWriteTokens=v;}
 public Long getTotalTokens(){return totalTokens;} public void setTotalTokens(Long v){totalTokens=v;}
 public Date getCreateTime(){return createTime;} public void setCreateTime(Date v){createTime=v;}
 public Date getUpdateTime(){return updateTime;} public void setUpdateTime(Date v){updateTime=v;}
 public Date getEndTime(){return endTime;} public void setEndTime(Date v){endTime=v;}
}