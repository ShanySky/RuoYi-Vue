package com.ruoyi.ai.mapper;
import java.util.List; import org.apache.ibatis.annotations.*; import com.ruoyi.ai.domain.AiMessage;
public interface AiMessageMapper {
 @Insert("insert into ai_message(conversation_id,sequence_no,role,content,tool_call_id,tool_name,tool_arguments,model_id,model_code,reasoning_effort,run_id,create_time) values(#{conversationId},#{sequenceNo},#{role},#{content},#{toolCallId},#{toolName},#{toolArguments},#{modelId},#{modelCode},#{reasoningEffort},#{runId},sysdate())") @Options(useGeneratedKeys=true,keyProperty="messageId") int insert(AiMessage m);
 String FIELDS="select message_id as messageId,conversation_id as conversationId,sequence_no as sequenceNo,role,content,tool_call_id as toolCallId,tool_name as toolName,tool_arguments as toolArguments,model_id as modelId,model_code as modelCode,reasoning_effort as reasoningEffort,run_id as runId,create_time as createTime ";
 @Select(FIELDS+"from ai_message where conversation_id=#{conversationId} order by sequence_no") List<AiMessage> selectByConversationId(Long conversationId);
 @Select(FIELDS+"from ai_message where conversation_id=#{conversationId} and sequence_no>#{after} order by sequence_no") List<AiMessage> selectAfter(@Param("conversationId")Long conversationId,@Param("after")int after);
 @Select(FIELDS+"from ai_message where conversation_id=#{conversationId} and sequence_no>#{after} and sequence_no<=#{upTo} order by sequence_no") List<AiMessage> selectRange(@Param("conversationId")Long conversationId,@Param("after")int after,@Param("upTo")int upTo);
 @Select("select coalesce(max(sequence_no),0)+1 from ai_message where conversation_id=#{conversationId}") int nextSequence(Long conversationId);
 @Select("select coalesce(max(sequence_no),0) from ai_message where conversation_id=#{conversationId}") int maxSequence(Long conversationId);
 @Select("select coalesce(max(sequence_no),0) from ai_message where conversation_id=#{conversationId} and role='USER'") int latestUserSequence(Long conversationId);
 @Select("select count(*) from ai_message where conversation_id=#{conversationId} and role='TOOL' and sequence_no > coalesce((select max(sequence_no) from ai_message where conversation_id=#{conversationId} and role='USER'),0)") int countToolResultsInCurrentTurn(Long conversationId);
}