package com.ruoyi.ai.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;

/** 授权摘要只用于使旧结果失效，实际行过滤仍由原业务数据范围负责。 */
public interface AiAuthorizationMapper
{
    @Select("select rd.role_id, rd.dept_id from sys_role_dept rd join sys_user_role ur on ur.role_id=rd.role_id "
            + "where ur.user_id=#{userId} order by rd.role_id, rd.dept_id")
    List<Map<String, Object>> roleDepartments(Long userId);

    @Select("select dept_id, parent_id, ancestors, status, del_flag from sys_dept order by dept_id")
    List<Map<String, Object>> departmentHierarchy();
}
