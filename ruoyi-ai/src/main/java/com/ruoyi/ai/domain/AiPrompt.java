package com.ruoyi.ai.domain;
import java.util.Date;
public class AiPrompt {
 private Long promptId; private String promptType,content,defaultContent,enabled,createBy,updateBy; private Integer versionNo; private Date createTime,updateTime;
 public Long getPromptId(){return promptId;} public void setPromptId(Long v){promptId=v;}
 public String getPromptType(){return promptType;} public void setPromptType(String v){promptType=v;}
 public String getContent(){return content;} public void setContent(String v){content=v;}
 public String getDefaultContent(){return defaultContent;} public void setDefaultContent(String v){defaultContent=v;}
 public Integer getVersionNo(){return versionNo;} public void setVersionNo(Integer v){versionNo=v;}
 public String getEnabled(){return enabled;} public void setEnabled(String v){enabled=v;}
 public String getCreateBy(){return createBy;} public void setCreateBy(String v){createBy=v;}
 public Date getCreateTime(){return createTime;} public void setCreateTime(Date v){createTime=v;}
 public String getUpdateBy(){return updateBy;} public void setUpdateBy(String v){updateBy=v;}
 public Date getUpdateTime(){return updateTime;} public void setUpdateTime(Date v){updateTime=v;}
}