package com.ruoyi.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiDataPolicyMapper
{
    record Policy(String scopeKey, String contractHash, boolean enabled, String fieldsJson, String operationsJson, long revision) { }

    @Select("select scope_key,contract_hash,enabled,fields_json,operations_json,revision from ai_data_policy where scope_key=#{key}")
    Policy get(String key);

    @Insert("insert into ai_data_policy(scope_key,contract_hash,enabled,fields_json,operations_json,revision,update_by,update_time) "
            + "values(#{key},#{hash},#{enabled},#{fields},#{operations},1,#{user},sysdate()) on duplicate key update "
            + "contract_hash=#{hash},enabled=#{enabled},fields_json=#{fields},operations_json=#{operations},"
            + "revision=revision+1,update_by=#{user},update_time=sysdate()")
    int save(@Param("key") String key, @Param("hash") String hash, @Param("enabled") boolean enabled,
            @Param("fields") String fields, @Param("operations") String operations, @Param("user") String user);
}
