package com.ruoyi.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import static com.ruoyi.ai.tool.ToolPolicyDefinition.objectSchema;
import static com.ruoyi.ai.tool.ToolPolicyDefinition.flexibleObjectSchema;

@Component
public class SystemToolPolicyProvider implements ToolPolicyProvider
{
    private final Map<String, ToolPolicyDefinition> policies;

    public SystemToolPolicyProvider()
    {
        Map<String, ToolPolicyDefinition> values = new LinkedHashMap<>();
        values.put("app_navigate", new ToolPolicyDefinition(
                        "UI", null, "导航到当前登录用户有权访问的 RuoYi 页面；导航本身不授予任何后端业务权限",
                        objectSchema(Map.of("path", Map.of("type", "string", "description", "目标页面绝对路径")),
                                List.of("path"))));
        values.put("page_system_user_search", new ToolPolicyDefinition(
                        "READ", "system:user:list", "在当前用户管理页设置查询条件并查询用户",
                        objectSchema(Map.of(
                                "userName", Map.of("type", "string", "description", "用户账号关键字"),
                                "phonenumber", Map.of("type", "string", "description", "手机号码"),
                                "status", Map.of("type", "string", "enum", List.of("0", "1"), "description", "0正常，1停用"),
                                "deptId", Map.of("type", "integer", "description", "部门ID"),
                                "dateRange", Map.of("type", "array", "items", Map.of("type", "string"), "description", "创建时间范围"),
                                "pageNum", Map.of("type", "integer", "description", "页码"),
                                "pageSize", Map.of("type", "integer", "description", "每页数量")),
                                List.of())));
        values.put("page_system_user_edit_open", new ToolPolicyDefinition(
                        "UI", "system:user:edit", "在当前用户管理页打开指定 userId 的用户编辑弹窗",
                        objectSchema(Map.of("userId", Map.of("type", "integer", "description", "用户ID")), List.of("userId"))));
        values.put("page_system_user_edit_set_fields", new ToolPolicyDefinition(
                        "UI", "system:user:edit", "修改当前已打开用户编辑表单的安全字段但不保存；仅支持用户昵称 nickName 等安全字段，不支持修改登录账号 userName",
                        objectSchema(Map.of(
                                "nickName", Map.of("type", "string", "description", "用户昵称"),
                                "deptId", Map.of("type", "integer", "description", "归属部门ID"),
                                "phonenumber", Map.of("type", "string", "description", "手机号码"),
                                "email", Map.of("type", "string", "description", "邮箱"),
                                "sex", Map.of("type", "string", "enum", List.of("0", "1", "2"), "description", "性别"),
                                "status", Map.of("type", "string", "enum", List.of("0", "1"), "description", "状态"),
                                "postIds", Map.of("type", "array", "items", Map.of("type", "integer"), "description", "岗位ID列表"),
                                "roleIds", Map.of("type", "array", "items", Map.of("type", "integer"), "description", "角色ID列表"),
                                "remark", Map.of("type", "string", "description", "备注")),
                                List.of())));
        values.put("page_system_user_edit_submit", new ToolPolicyDefinition(
                        "WRITE", "system:user:edit", "提交当前用户编辑表单并真实写入系统",
                        objectSchema(Map.of(), List.of())));
        values.put("page_system_role_add_submit", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "system:role:add", "创建新角色并写入角色权限配置",
                        objectSchema(Map.of(), List.of())));
        values.put("page_system_role_edit_submit", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "system:role:edit", "保存角色配置修改；可能影响系统权限边界",
                        objectSchema(Map.of(), List.of())));
        values.put("page_system_user_auth_role_view", new ToolPolicyDefinition(
                        "READ", "system:user:query", "查看指定用户当前可分配角色及已授权角色",
                        objectSchema(Map.of(), List.of())));
        values.put("page_system_user_auth_role_select", new ToolPolicyDefinition(
                        "UI", "system:user:edit", "在分配角色页面选择准备授予用户的角色，但不保存",
                        objectSchema(Map.of(
                                "roleIds", Map.of("type", "array", "items", Map.of("type", "integer"),
                                        "description", "准备授予用户的角色ID列表")),
                                List.of("roleIds"))));
        values.put("page_system_user_auth_role_submit", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "system:user:edit", "提交用户角色授权；会改变该用户实际权限",
                        objectSchema(Map.of(), List.of())));
        values.put("page_system_role_data_scope_open", new ToolPolicyDefinition(
                        "UI", "system:role:edit", "打开指定角色的数据权限配置",
                        objectSchema(Map.of("roleId", Map.of("type", "integer", "description", "角色ID")), List.of("roleId"))));
        values.put("page_system_role_data_scope_set_fields", new ToolPolicyDefinition(
                        "UI", "system:role:edit", "修改当前角色的数据范围和自定义部门选择但不保存",
                        objectSchema(Map.of(
                                "dataScope", Map.of("type", "string", "enum", List.of("1", "2", "3", "4", "5")),
                                "deptIds", Map.of("type", "array", "items", Map.of("type", "integer")),
                                "deptCheckStrictly", Map.of("type", "boolean")), List.of())));
        values.put("page_system_role_data_scope_submit", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "system:role:edit", "提交角色数据权限；会改变该角色可访问的数据范围",
                        objectSchema(Map.of(), List.of())));
        values.put("page_system_role_auth_user_search", new ToolPolicyDefinition(
                        "READ", "system:role:list", "查询指定角色当前已授权用户",
                        objectSchema(Map.of(
                                "userName", Map.of("type", "string"),
                                "phonenumber", Map.of("type", "string"),
                                "pageNum", Map.of("type", "integer"),
                                "pageSize", Map.of("type", "integer")), List.of())));
        values.put("page_system_role_auth_user_candidates", new ToolPolicyDefinition(
                        "READ", "system:role:list", "查询当前角色尚未授权、可加入的候选用户",
                        objectSchema(Map.of(
                                "userName", Map.of("type", "string"),
                                "phonenumber", Map.of("type", "string"),
                                "pageNum", Map.of("type", "integer"),
                                "pageSize", Map.of("type", "integer")), List.of())));
        values.put("page_system_role_auth_user_assign_users", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "system:role:edit", "向当前角色批量授权用户",
                        objectSchema(Map.of("userIds", Map.of("type", "array", "items", Map.of("type", "integer"))), List.of("userIds"))));
        values.put("page_system_role_auth_user_cancel_user", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "system:role:edit", "取消单个用户的当前角色授权",
                        objectSchema(Map.of("userId", Map.of("type", "integer")), List.of("userId"))));
        values.put("page_system_role_auth_user_cancel_users", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "system:role:edit", "批量取消用户的当前角色授权",
                        objectSchema(Map.of("userIds", Map.of("type", "array", "items", Map.of("type", "integer"))), List.of("userIds"))));
        values.put("page_system_dept_sort_submit", new ToolPolicyDefinition(
                        "WRITE", "system:dept:edit", "保存部门显示排序",
                        objectSchema(Map.of("items", Map.of("type", "array", "items", Map.of("type", "object"))), List.of("items"))));
        values.put("page_system_menu_sort_submit", new ToolPolicyDefinition(
                        "WRITE", "system:menu:edit", "保存菜单显示排序",
                        objectSchema(Map.of("items", Map.of("type", "array", "items", Map.of("type", "object"))), List.of("items"))));
        values.put("page_system_notice_read_users", new ToolPolicyDefinition(
                        "READ", "system:notice:list", "打开公告已读用户并返回当前已读用户列表",
                        objectSchema(Map.of("noticeId", Map.of("type", "integer")), List.of("noticeId"))));
        values.put("page_system_profile_view", new ToolPolicyDefinition(
                        "READ", null, "查看当前登录用户自己的个人资料",
                        objectSchema(Map.of(), List.of())));
        values.put("page_system_profile_set_fields", new ToolPolicyDefinition(
                        "UI", null, "修改当前登录用户自己的基本资料表单但不保存",
                        objectSchema(Map.of(
                                "nickName", Map.of("type", "string"),
                                "phonenumber", Map.of("type", "string"),
                                "email", Map.of("type", "string"),
                                "sex", Map.of("type", "string", "enum", List.of("0", "1", "2"))), List.of())));
        values.put("page_system_profile_submit", new ToolPolicyDefinition(
                        "WRITE", null, "保存当前登录用户自己的基本资料",
                        objectSchema(Map.of(), List.of())));
        values.put("page_system_profile_select_tab", new ToolPolicyDefinition(
                        "UI", null, "切换个人中心的基本资料或修改密码页签；密码字段不向 AI 暴露",
                        objectSchema(Map.of("tab", Map.of("type", "string", "enum", List.of("userinfo", "resetPwd"))), List.of("tab"))));

        // User-management special actions deliberately override Generic CRUD so the model
        // receives the same narrow argument surface the representative page actually accepts.
        values.put("page_system_user_view", new ToolPolicyDefinition(
                "READ", "system:user:list", "查看指定用户详情",
                objectSchema(Map.of("userId", Map.of("type", "integer")), java.util.List.of("userId"))));
        values.put("page_system_user_delete", new ToolPolicyDefinition(
                "DANGEROUS_WRITE", "system:user:remove", "删除指定用户；该动作会真实删除业务数据",
                objectSchema(Map.of("userIds", Map.of("type", "array", "items", Map.of("type", "integer"))),
                        java.util.List.of("userIds"))));
        values.put("page_system_user_change_status", new ToolPolicyDefinition(
                "WRITE", "system:user:edit", "启用或停用指定用户",
                objectSchema(Map.of(
                        "userId", Map.of("type", "integer"),
                        "status", Map.of("type", "string", "enum", java.util.List.of("0", "1"))),
                        java.util.List.of("userId", "status"))));
        values.put("page_system_user_reset_password", new ToolPolicyDefinition(
                "DANGEROUS_WRITE", "system:user:resetPwd", "重置指定用户密码；新密码只用于本次受控调用",
                objectSchema(Map.of(
                        "userId", Map.of("type", "integer"),
                        "password", Map.of("type", "string")),
                        java.util.List.of("userId", "password"))));
        values.put("page_system_user_auth_role", new ToolPolicyDefinition(
                "UI", "system:user:edit", "进入指定用户的角色分配页面，但不直接修改授权",
                objectSchema(Map.of("userId", Map.of("type", "integer")), java.util.List.of("userId"))));
        values.put("page_system_user_import_open", new ToolPolicyDefinition(
                "UI", "system:user:import", "打开用户导入窗口；文件选择和上传仍由用户完成",
                objectSchema(Map.of(), java.util.List.of())));
        values.put("page_system_user_export", new ToolPolicyDefinition(
                "READ", "system:user:export", "按当前查询条件发起用户导出",
                objectSchema(Map.of(), java.util.List.of())));

        policies = Map.copyOf(values);
    }

    @Override
    public ToolPolicyDefinition resolve(String toolName)
    {
        return policies.get(toolName);
    }
}
