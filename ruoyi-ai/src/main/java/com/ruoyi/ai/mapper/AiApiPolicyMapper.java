package com.ruoyi.ai.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiApiPolicyMapper
{
    record Policy(String capabilityId, String contractHash, boolean enabled, long revision) { }
    record Label(String permission, String title) { }

    @Select("select m.perms,case when m.menu_type='F' then concat(p.menu_name,' / ',m.menu_name) "
            + "else m.menu_name end from sys_menu m left join sys_menu p on p.menu_id=m.parent_id "
            + "where m.perms is not null and m.perms<>'' order by m.menu_type,m.menu_id")
    List<Label> labels();

    @Select("select capability_id, contract_hash, enabled, revision from ai_api_policy order by capability_id")
    List<Policy> all();

    @Select("select capability_id, contract_hash, enabled, revision from ai_api_policy where capability_id=#{id}")
    Policy get(String id);

    @Insert("insert into ai_api_policy(capability_id,contract_hash,enabled,revision,update_by,update_time) "
            + "values(#{id},#{hash},#{enabled},1,#{user},sysdate()) on duplicate key update "
            + "contract_hash=#{hash},enabled=#{enabled},revision=revision+1,update_by=#{user},update_time=sysdate()")
    int save(@Param("id") String id, @Param("hash") String hash, @Param("enabled") boolean enabled,
            @Param("user") String user);
}
