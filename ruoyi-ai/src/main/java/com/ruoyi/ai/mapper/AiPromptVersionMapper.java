package com.ruoyi.ai.mapper;

import java.util.List;
import org.apache.ibatis.annotations.*;
import com.ruoyi.ai.domain.AiPromptVersion;

public interface AiPromptVersionMapper
{
    String FIELDS="select prompt_version_id as promptVersionId,prompt_type as promptType,version_no as versionNo,content,create_by as createBy,create_time as createTime ";

    @Insert("insert into ai_prompt_version(prompt_type,version_no,content,create_by,create_time) values(#{promptType},#{versionNo},#{content},#{createBy},sysdate())")
    @Options(useGeneratedKeys=true,keyProperty="promptVersionId")
    int insert(AiPromptVersion version);

    @Select(FIELDS+"from ai_prompt_version where prompt_type=#{type} and version_no=#{versionNo} limit 1")
    AiPromptVersion selectByTypeAndVersion(@Param("type")String type,@Param("versionNo")Integer versionNo);

    @Select(FIELDS+"from ai_prompt_version where prompt_type=#{type} and content=#{content} order by version_no desc limit 1")
    AiPromptVersion selectLatestByContent(@Param("type")String type,@Param("content")String content);

    @Select(FIELDS+"from ai_prompt_version where prompt_type=#{type} order by version_no")
    List<AiPromptVersion> selectByType(String type);
}
