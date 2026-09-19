package com.ruoyi.ai.domain;

import java.util.Date;

public class AiPromptVersion
{
    private Long promptVersionId;
    private String promptType;
    private Integer versionNo;
    private String content;
    private String createBy;
    private Date createTime;

    public Long getPromptVersionId(){return promptVersionId;} public void setPromptVersionId(Long v){promptVersionId=v;}
    public String getPromptType(){return promptType;} public void setPromptType(String v){promptType=v;}
    public Integer getVersionNo(){return versionNo;} public void setVersionNo(Integer v){versionNo=v;}
    public String getContent(){return content;} public void setContent(String v){content=v;}
    public String getCreateBy(){return createBy;} public void setCreateBy(String v){createBy=v;}
    public Date getCreateTime(){return createTime;} public void setCreateTime(Date v){createTime=v;}
}
