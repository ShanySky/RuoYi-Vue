package com.ruoyi.ai.mapper;
import java.util.List; import org.apache.ibatis.annotations.*; import com.ruoyi.ai.domain.AiPrompt;
public interface AiPromptMapper {
 String FIELDS="select prompt_id as promptId,prompt_type as promptType,content,default_content as defaultContent,version_no as versionNo,enabled,create_by as createBy,create_time as createTime,update_by as updateBy,update_time as updateTime ";
 @Select(FIELDS+"from ai_prompt order by prompt_type") List<AiPrompt> selectAll();
 @Select(FIELDS+"from ai_prompt where prompt_type=#{type} limit 1") AiPrompt selectByType(String type);
 @Select(FIELDS+"from ai_prompt where prompt_type=#{type} limit 1 for update") AiPrompt selectByTypeForUpdate(String type);
 @Update("update ai_prompt set content=#{content},version_no=#{versionNo},enabled=#{enabled},update_by=#{username},update_time=sysdate() where prompt_type=#{type}") int updateContentVersion(@Param("type")String type,@Param("content")String content,@Param("versionNo")Integer versionNo,@Param("enabled")String enabled,@Param("username")String username);
 @Update("update ai_prompt set enabled=#{enabled},update_by=#{username},update_time=sysdate() where prompt_type=#{type}") int updateEnabled(@Param("type")String type,@Param("enabled")String enabled,@Param("username")String username);
}
