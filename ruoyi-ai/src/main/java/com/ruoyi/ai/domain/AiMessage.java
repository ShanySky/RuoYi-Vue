package com.ruoyi.ai.domain;
import java.util.Date;
public class AiMessage {
 private Long messageId,conversationId,modelId,runId; private Integer sequenceNo; private String role,content,toolCallId,toolName,toolArguments,modelCode,reasoningEffort; private Date createTime;
 public Long getMessageId(){return messageId;} public void setMessageId(Long v){messageId=v;} public Long getConversationId(){return conversationId;} public void setConversationId(Long v){conversationId=v;}
 public Integer getSequenceNo(){return sequenceNo;} public void setSequenceNo(Integer v){sequenceNo=v;} public String getRole(){return role;} public void setRole(String v){role=v;}
 public String getContent(){return content;} public void setContent(String v){content=v;} public String getToolCallId(){return toolCallId;} public void setToolCallId(String v){toolCallId=v;}
 public String getToolName(){return toolName;} public void setToolName(String v){toolName=v;} public String getToolArguments(){return toolArguments;} public void setToolArguments(String v){toolArguments=v;}
 public Long getModelId(){return modelId;} public void setModelId(Long v){modelId=v;} public String getModelCode(){return modelCode;} public void setModelCode(String v){modelCode=v;}
 public String getReasoningEffort(){return reasoningEffort;} public void setReasoningEffort(String v){reasoningEffort=v;} public Long getRunId(){return runId;} public void setRunId(Long v){runId=v;}
 public Date getCreateTime(){return createTime;} public void setCreateTime(Date v){createTime=v;}
}