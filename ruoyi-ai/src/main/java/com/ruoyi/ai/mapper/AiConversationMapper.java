package com.ruoyi.ai.mapper;
import java.util.List; import org.apache.ibatis.annotations.*; import com.ruoyi.ai.domain.AiConversation;
public interface AiConversationMapper {
 String FIELDS="select conversation_id as conversationId,user_id as userId,model_id as modelId,reasoning_effort as reasoningEffort,title,route,status,create_time as createTime,update_time as updateTime ";
 @Insert("insert into ai_conversation(user_id,model_id,reasoning_effort,title,route,status,create_time,update_time) values(#{userId},#{modelId},#{reasoningEffort},#{title},#{route},#{status},sysdate(),sysdate())") @Options(useGeneratedKeys=true,keyProperty="conversationId") int insert(AiConversation c);
 @Select(FIELDS+"from ai_conversation where conversation_id=#{conversationId}") AiConversation selectById(Long conversationId);
 @Select(FIELDS+"from ai_conversation where conversation_id=#{conversationId} for update") AiConversation lockById(Long conversationId);
 @Select(FIELDS+"from ai_conversation where user_id=#{userId} and status<>'DELETED' and (#{keyword} is null or #{keyword}='' or title like concat('%',#{keyword},'%')) order by update_time desc limit 100") List<AiConversation> selectByUser(@Param("userId")Long userId,@Param("keyword")String keyword);
 @Select(FIELDS+"from ai_conversation where user_id=#{userId} and status<>'DELETED' order by update_time desc limit 1") AiConversation selectLastByUser(Long userId);
 @Update("update ai_conversation set route=#{route},update_time=sysdate() where conversation_id=#{conversationId} and user_id=#{userId}") int touch(@Param("conversationId")Long conversationId,@Param("userId")Long userId,@Param("route")String route);
 @Update("update ai_conversation set model_id=#{modelId},reasoning_effort=#{reasoningEffort},update_time=sysdate() where conversation_id=#{conversationId} and user_id=#{userId}") int updateSelection(@Param("conversationId")Long conversationId,@Param("userId")Long userId,@Param("modelId")Long modelId,@Param("reasoningEffort")String reasoningEffort);
 @Update("update ai_conversation set title=#{title},update_time=sysdate() where conversation_id=#{conversationId} and user_id=#{userId}") int updateTitle(@Param("conversationId")Long conversationId,@Param("userId")Long userId,@Param("title")String title);
 @Update("update ai_conversation set status=#{status},update_time=sysdate() where conversation_id=#{conversationId} and user_id=#{userId}") int updateStatus(@Param("conversationId")Long conversationId,@Param("userId")Long userId,@Param("status")String status);
}