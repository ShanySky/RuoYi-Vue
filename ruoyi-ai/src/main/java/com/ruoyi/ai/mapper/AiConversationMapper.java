package com.ruoyi.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.ruoyi.ai.domain.AiConversation;

public interface AiConversationMapper
{
    @Insert("insert into ai_conversation(user_id, model_id, reasoning_effort, title, route, status, create_time, update_time) "
            + "values(#{userId}, #{modelId}, #{reasoningEffort}, #{title}, #{route}, #{status}, sysdate(), sysdate())")
    @Options(useGeneratedKeys = true, keyProperty = "conversationId")
    int insert(AiConversation conversation);

    @Select("select conversation_id as conversationId, user_id as userId, model_id as modelId, reasoning_effort as reasoningEffort, title, route, status, "
            + "create_time as createTime, update_time as updateTime from ai_conversation where conversation_id=#{conversationId}")
    AiConversation selectById(Long conversationId);

    @Update("update ai_conversation set route=#{route}, update_time=sysdate() where conversation_id=#{conversationId} and user_id=#{userId}")
    int touch(@Param("conversationId") Long conversationId, @Param("userId") Long userId, @Param("route") String route);

    @Update("update ai_conversation set model_id=#{modelId}, reasoning_effort=#{reasoningEffort}, update_time=sysdate() "
            + "where conversation_id=#{conversationId} and user_id=#{userId}")
    int updateSelection(@Param("conversationId") Long conversationId, @Param("userId") Long userId,
            @Param("modelId") Long modelId, @Param("reasoningEffort") String reasoningEffort);
}
