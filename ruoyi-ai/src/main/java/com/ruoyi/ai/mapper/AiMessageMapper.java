package com.ruoyi.ai.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import com.ruoyi.ai.domain.AiMessage;

public interface AiMessageMapper
{
    @Insert("insert into ai_message(conversation_id, sequence_no, role, content, tool_call_id, tool_name, tool_arguments, create_time) "
            + "values(#{conversationId}, #{sequenceNo}, #{role}, #{content}, #{toolCallId}, #{toolName}, #{toolArguments}, sysdate())")
    @Options(useGeneratedKeys = true, keyProperty = "messageId")
    int insert(AiMessage message);

    @Select("select message_id as messageId, conversation_id as conversationId, sequence_no as sequenceNo, role, content, "
            + "tool_call_id as toolCallId, tool_name as toolName, tool_arguments as toolArguments, create_time as createTime "
            + "from ai_message where conversation_id=#{conversationId} order by sequence_no")
    List<AiMessage> selectByConversationId(Long conversationId);

    @Select("select coalesce(max(sequence_no),0)+1 from ai_message where conversation_id=#{conversationId}")
    int nextSequence(Long conversationId);

    @Select("select count(*) from ai_message where conversation_id=#{conversationId} and role='TOOL' and sequence_no > "
            + "coalesce((select max(sequence_no) from ai_message where conversation_id=#{conversationId} and role='USER'),0)")
    int countToolResultsInCurrentTurn(Long conversationId);
}
