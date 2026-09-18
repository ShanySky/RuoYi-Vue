package com.ruoyi.ai.mapper;
import java.util.List; import org.apache.ibatis.annotations.*; import com.ruoyi.ai.domain.AiPrompt;
public interface AiPromptMapper {
 String FIELDS="select prompt_id as promptId,prompt_type as promptType,content,default_content as defaultContent,version_no as versionNo,enabled,create_by as createBy,create_time as createTime,update_by as updateBy,update_time as updateTime ";
 @Select(FIELDS+"from ai_prompt order by prompt_type") List<AiPrompt> selectAll();
 @Select(FIELDS+"from ai_prompt where prompt_type=#{type} limit 1") AiPrompt selectByType(String type);
 @Update("update ai_prompt set content=coalesce(#{content},content),enabled=coalesce(#{enabled},enabled),version_no=version_no+1,update_by=#{username},update_time=sysdate() where prompt_type=#{type}") int update(@Param("type")String type,@Param("content")String content,@Param("enabled")String enabled,@Param("username")String username);
 @Update("update ai_prompt set content=default_content,enabled='0',version_no=version_no+1,update_by=#{username},update_time=sysdate() where prompt_type=#{type}") int restoreDefault(@Param("type")String type,@Param("username")String username);
}
