package com.ruoyi.ai.dto;
public class AiPreferenceRequest {
 private Long defaultModelId; private String defaultReasoningEffort; private String chatFontSize; private String sendShortcut; private Boolean doubleEscEnabled,historyEntryVisible,autoRestoreLastConversation; private String assistantOpenMode;
 public Long getDefaultModelId(){return defaultModelId;} public void setDefaultModelId(Long v){defaultModelId=v;}
 public String getDefaultReasoningEffort(){return defaultReasoningEffort;} public void setDefaultReasoningEffort(String v){defaultReasoningEffort=v;}
 public String getChatFontSize(){return chatFontSize;} public void setChatFontSize(String v){chatFontSize=v;}
 public String getSendShortcut(){return sendShortcut;} public void setSendShortcut(String v){sendShortcut=v;}
 public Boolean getDoubleEscEnabled(){return doubleEscEnabled;} public void setDoubleEscEnabled(Boolean v){doubleEscEnabled=v;}
 public Boolean getHistoryEntryVisible(){return historyEntryVisible;} public void setHistoryEntryVisible(Boolean v){historyEntryVisible=v;}
 public Boolean getAutoRestoreLastConversation(){return autoRestoreLastConversation;} public void setAutoRestoreLastConversation(Boolean v){autoRestoreLastConversation=v;}
 public String getAssistantOpenMode(){return assistantOpenMode;} public void setAssistantOpenMode(String v){assistantOpenMode=v;}
}