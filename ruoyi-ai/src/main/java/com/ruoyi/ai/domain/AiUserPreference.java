package com.ruoyi.ai.domain;
import java.util.Date;
public class AiUserPreference {
 private Long userId,defaultModelId; private String defaultReasoningEffort,chatFontSize,sendShortcut,doubleEscEnabled,historyEntryVisible,autoRestoreLastConversation,assistantOpenMode; private Date createTime,updateTime;
 public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
 public Long getDefaultModelId(){return defaultModelId;} public void setDefaultModelId(Long v){defaultModelId=v;}
 public String getDefaultReasoningEffort(){return defaultReasoningEffort;} public void setDefaultReasoningEffort(String v){defaultReasoningEffort=v;}
 public String getChatFontSize(){return chatFontSize;} public void setChatFontSize(String v){chatFontSize=v;}
 public String getSendShortcut(){return sendShortcut;} public void setSendShortcut(String v){sendShortcut=v;}
 public String getDoubleEscEnabled(){return doubleEscEnabled;} public void setDoubleEscEnabled(String v){doubleEscEnabled=v;}
 public String getHistoryEntryVisible(){return historyEntryVisible;} public void setHistoryEntryVisible(String v){historyEntryVisible=v;}
 public String getAutoRestoreLastConversation(){return autoRestoreLastConversation;} public void setAutoRestoreLastConversation(String v){autoRestoreLastConversation=v;}
 public String getAssistantOpenMode(){return assistantOpenMode;} public void setAssistantOpenMode(String v){assistantOpenMode=v;}
 public Date getCreateTime(){return createTime;} public void setCreateTime(Date v){createTime=v;}
 public Date getUpdateTime(){return updateTime;} public void setUpdateTime(Date v){updateTime=v;}
}