package com.ruoyi.ai.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiWorkspaceMapper
{
    record Policy(boolean enabled, long revision) { }
    record Artifact(String id, Long userId, Long conversationId, Long runId, String name, long bytes,
            String sha256, long expiresAt, Date createdAt) { }
    String FIELDS = "select artifact_id as id,user_id as userId,conversation_id as conversationId,run_id as runId,"
            + "file_name as name,file_bytes as bytes,sha256,expire_time as expiresAt,create_time as createdAt from ai_artifact ";

    @Select("select enabled,revision from ai_workspace_policy where policy_id=1") Policy policy();
    @Update("update ai_workspace_policy set enabled=#{enabled},revision=revision+1,update_by=#{user},update_time=sysdate() "
            + "where policy_id=1 and revision=#{revision}")
    int configure(@Param("enabled") boolean enabled, @Param("revision") long revision, @Param("user") String user);
    @Insert("insert into ai_artifact(artifact_id,user_id,conversation_id,run_id,file_name,file_bytes,sha256,expire_time,create_time) "
            + "values(#{id},#{userId},#{conversationId},#{runId},#{name},#{bytes},#{sha256},#{expiresAt},sysdate())")
    int insert(Map<String, Object> value);
    @Select(FIELDS + "where artifact_id=#{id}") Artifact get(String id);
    @Select(FIELDS + "where conversation_id=#{id} and expire_time>unix_timestamp()*1000 order by create_time desc") List<Artifact> list(Long id);
    @Select(FIELDS + "where expire_time<=unix_timestamp()*1000 or not exists(select 1 from ai_conversation c where c.conversation_id=ai_artifact.conversation_id)")
    List<Artifact> expired();
    @Delete("delete from ai_artifact where artifact_id=#{id}") int delete(String id);
}
