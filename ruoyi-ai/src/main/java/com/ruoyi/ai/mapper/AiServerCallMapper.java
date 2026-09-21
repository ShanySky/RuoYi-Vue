package com.ruoyi.ai.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiServerCallMapper
{
    record Call(String callId, Long conversationId, Long runId, Long userId, String toolName,
            String capabilityId, String authorizationHash, String riskLevel, String status,
            String resultId, String resultJson, String toolResultJson, String errorCode) { }
    record Source(String capabilityId, String authorizationHash) { }
    record Loaded(String capabilityId, String contractHash) { }

    String FIELDS = "select call_id,conversation_id,run_id,user_id,tool_name,capability_id,authorization_hash,"
            + "risk_level,status,result_id,result_json,tool_result_json,error_code from ai_server_call ";

    @Insert("insert into ai_server_call(call_id,conversation_id,run_id,user_id,tool_name,capability_id,"
            + "authorization_hash,risk_level,status,dedupe_key,create_time,expire_time) "
            + "values(#{callId},#{conversationId},#{runId},#{userId},#{toolName},#{capabilityId},"
            + "#{authorizationHash},#{riskLevel},'PENDING',#{dedupeKey},sysdate(),date_add(sysdate(),interval 30 minute))")
    int insert(Map<String, Object> call);

    @Select(FIELDS + "where call_id=#{id}")
    Call get(String id);

    @Select(FIELDS + "where result_id=#{id} and expire_time>sysdate() and result_json is not null")
    Call result(String id);

    @Update("update ai_server_call c join ai_run r on r.run_id=c.run_id "
            + "join ai_pending_tool_call p on p.call_id=c.call_id and p.conversation_id=c.conversation_id "
            + "set c.status='EXECUTING',c.start_time=sysdate() where c.call_id=#{id} and c.status='PENDING' "
            + "and r.status='WAITING_TOOL' and p.status='PENDING' and p.expire_time>sysdate()")
    int claim(String id);

    @Update("update ai_server_call set status=#{status},result_id=#{resultId},result_json=#{result},"
            + "tool_result_json=#{toolResult},error_code=#{error},end_time=sysdate() "
            + "where call_id=#{id} and status='EXECUTING'")
    int finish(@Param("id") String id, @Param("status") String status, @Param("resultId") String resultId,
            @Param("result") String result, @Param("toolResult") String toolResult, @Param("error") String error);

    @Select("select count(*) from ai_server_call where result_json is not null and expire_time>sysdate() "
            + "and (#{userId} is null or user_id=#{userId})")
    int resultCount(@Param("userId") Long userId);

    @Select("select guard_id from ai_server_result_guard where guard_id=1 for update")
    int lockResultQuota();

    @Select("select count(*) from ai_server_call where conversation_id=#{conversationId} and risk_level='WRITE' "
            + "and status in ('EXECUTING','UNKNOWN')")
    int unknownWrites(Long conversationId);

    @Insert("insert ignore into ai_business_source(conversation_id,capability_id,authorization_hash) "
            + "values(#{conversationId},#{capabilityId},#{authorizationHash})")
    int addSource(@Param("conversationId") Long conversationId, @Param("capabilityId") String capabilityId,
            @Param("authorizationHash") String authorizationHash);

    @Select("select capability_id,authorization_hash from ai_business_source where conversation_id=#{id}")
    List<Source> sources(Long id);

    @Insert("insert into ai_api_loaded(run_id,capability_id,contract_hash,load_time) "
            + "values(#{runId},#{id},#{hash},sysdate()) on duplicate key update contract_hash=#{hash},load_time=sysdate()")
    int load(@Param("runId") Long runId, @Param("id") String id, @Param("hash") String hash);

    @Select("select capability_id,contract_hash from ai_api_loaded where run_id=#{id} order by load_time desc,capability_id limit 8")
    List<Loaded> loaded(Long id);

    @Update("update ai_server_call set result_json=null,tool_result_json=null "
            + "where expire_time<sysdate() and (result_json is not null or tool_result_json is not null)")
    int expireResults();

    @Update("update ai_server_call set status='UNKNOWN',error_code='PROCESS_INTERRUPTED',end_time=sysdate() "
            + "where status='EXECUTING' and start_time<date_sub(sysdate(),interval 2 minute)")
    int recoverInterrupted();

    @Update("update ai_server_call set status='UNKNOWN',error_code='PROCESS_INTERRUPTED',end_time=sysdate() where status='EXECUTING'")
    int interruptAllExecuting();

    @Delete("delete l from ai_api_loaded l left join ai_run r on r.run_id=l.run_id "
            + "where r.run_id is null or (r.end_time is not null and r.end_time<date_sub(sysdate(),interval 30 minute))")
    int cleanLoaded();

    @Delete("delete c from ai_server_call c left join ai_conversation v on v.conversation_id=c.conversation_id "
            + "where v.conversation_id is null")
    int cleanOrphanCalls();

    @Delete("delete s from ai_business_source s left join ai_conversation v on v.conversation_id=s.conversation_id "
            + "where v.conversation_id is null")
    int cleanOrphanSources();
}
