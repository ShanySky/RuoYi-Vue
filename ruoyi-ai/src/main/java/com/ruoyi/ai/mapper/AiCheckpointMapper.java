package com.ruoyi.ai.mapper;
import org.apache.ibatis.annotations.*; import com.ruoyi.ai.domain.AiCheckpoint;
public interface AiCheckpointMapper {
 @Insert("insert into ai_checkpoint(conversation_id,run_id,covered_sequence_no,summary,model_id,model_code,estimated_tokens,status,create_time) values(#{conversationId},#{runId},#{coveredSequenceNo},#{summary},#{modelId},#{modelCode},#{estimatedTokens},#{status},sysdate())") @Options(useGeneratedKeys=true,keyProperty="checkpointId") int insert(AiCheckpoint c);
 @Select("select checkpoint_id as checkpointId,conversation_id as conversationId,run_id as runId,covered_sequence_no as coveredSequenceNo,summary,model_id as modelId,model_code as modelCode,estimated_tokens as estimatedTokens,status,create_time as createTime from ai_checkpoint where conversation_id=#{conversationId} and status='ACTIVE' order by checkpoint_id desc limit 1") AiCheckpoint selectLatest(Long conversationId);
}