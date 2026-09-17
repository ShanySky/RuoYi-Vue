package com.ruoyi.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.ruoyi.ai.domain.AiPendingToolCall;

public interface AiPendingToolCallMapper
{
    @Insert("insert into ai_pending_tool_call(call_id, conversation_id, user_id, tool_name, arguments_json, risk_level, status, create_time, expire_time) "
            + "values(#{callId}, #{conversationId}, #{userId}, #{toolName}, #{argumentsJson}, #{riskLevel}, 'PENDING', sysdate(), date_add(sysdate(), interval 10 minute))")
    @Options(useGeneratedKeys = true, keyProperty = "pendingId")
    int insert(AiPendingToolCall pending);

    @Select("select pending_id as pendingId, call_id as callId, conversation_id as conversationId, user_id as userId, "
            + "tool_name as toolName, arguments_json as argumentsJson, risk_level as riskLevel, status, "
            + "create_time as createTime, expire_time as expireTime, resolved_time as resolvedTime "
            + "from ai_pending_tool_call where conversation_id=#{conversationId} and call_id=#{callId} "
            + "and status='PENDING' and expire_time > sysdate() limit 1")
    AiPendingToolCall selectByCall(@Param("conversationId") Long conversationId, @Param("callId") String callId);

    @Update("update ai_pending_tool_call set status='RESOLVED', resolved_time=sysdate() "
            + "where pending_id=#{pendingId} and status='PENDING' and expire_time > sysdate()")
    int resolve(Long pendingId);
}
