package com.ruoyi.ai.dto;
public class AiModelRuntimeSettingsRequest {
 private Integer contextWindowTokens,compactionThresholdPercent; private Boolean autoCompaction;
 public Integer getContextWindowTokens(){return contextWindowTokens;} public void setContextWindowTokens(Integer v){contextWindowTokens=v;}
 public Integer getCompactionThresholdPercent(){return compactionThresholdPercent;} public void setCompactionThresholdPercent(Integer v){compactionThresholdPercent=v;}
 public Boolean getAutoCompaction(){return autoCompaction;} public void setAutoCompaction(Boolean v){autoCompaction=v;}
}