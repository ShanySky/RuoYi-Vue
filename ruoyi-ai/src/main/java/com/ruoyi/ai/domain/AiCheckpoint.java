package com.ruoyi.ai.domain;
import java.util.Date;
public class AiCheckpoint {
 private Long checkpointId,conversationId,runId,modelId; private Integer coveredSequenceNo,estimatedTokens; private String summary,modelCode,status; private Date createTime;
 public Long getCheckpointId(){return checkpointId;} public void setCheckpointId(Long v){checkpointId=v;}
 public Long getConversationId(){return conversationId;} public void setConversationId(Long v){conversationId=v;}
 public Long getRunId(){return runId;} public void setRunId(Long v){runId=v;}
 public Integer getCoveredSequenceNo(){return coveredSequenceNo;} public void setCoveredSequenceNo(Integer v){coveredSequenceNo=v;}
 public String getSummary(){return summary;} public void setSummary(String v){summary=v;}
 public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;}
 public String getModelCode(){return modelCode;} public void setModelCode(String v){modelCode=v;}
 public Integer getEstimatedTokens(){return estimatedTokens;} public void setEstimatedTokens(Integer v){estimatedTokens=v;}
 public String getStatus(){return status;} public void setStatus(String v){status=v;}
 public Date getCreateTime(){return createTime;} public void setCreateTime(Date v){createTime=v;}
}