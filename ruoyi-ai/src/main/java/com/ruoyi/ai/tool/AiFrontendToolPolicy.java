package com.ruoyi.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.ruoyi.ai.dto.AiFrontendToolDefinition;
import com.ruoyi.framework.web.service.PermissionService;

@Service
public class AiFrontendToolPolicy
{
    private final PermissionService permissionService;
    private final Map<String, ToolPolicy> allowlist = new LinkedHashMap<>();

    public AiFrontendToolPolicy(PermissionService permissionService)
    {
        this.permissionService = permissionService;
        allowlist.put("app_navigate", new ToolPolicy(
                "UI", null, "导航到当前登录用户有权访问的 RuoYi 页面；导航本身不授予任何后端业务权限",
                objectSchema(Map.of("path", Map.of("type", "string", "description", "目标页面绝对路径")),
                        List.of("path"))));
        allowlist.put("page_monitor_job_view", new ToolPolicy(
                "READ", "monitor:job:query", "查看指定定时任务的详细配置",
                objectSchema(Map.of("jobId", Map.of("type", "integer", "description", "任务ID")), List.of("jobId"))));
        allowlist.put("page_monitor_job_change_status", new ToolPolicy(
                "DANGEROUS_WRITE", "monitor:job:changeStatus", "启用或停用指定定时任务；会改变调度器实际运行状态",
                objectSchema(Map.of(
                        "jobId", Map.of("type", "integer", "description", "任务ID"),
                        "status", Map.of("type", "string", "enum", List.of("0", "1"), "description", "0正常，1暂停")),
                        List.of("jobId", "status"))));
        allowlist.put("page_monitor_job_run_now", new ToolPolicy(
                "DANGEROUS_WRITE", "monitor:job:changeStatus", "立即执行一次指定定时任务；该动作可能触发实际业务副作用",
                objectSchema(Map.of(
                        "jobId", Map.of("type", "integer", "description", "任务ID"),
                        "jobGroup", Map.of("type", "string", "description", "任务组")),
                        List.of("jobId", "jobGroup"))));
        allowlist.put("page_monitor_logininfor_unlock", new ToolPolicy(
                "WRITE", "monitor:logininfor:unlock", "解除指定用户的登录锁定状态",
                objectSchema(Map.of("userName", Map.of("type", "string", "description", "登录账号")), List.of("userName"))));
        allowlist.put("page_monitor_logininfor_clean", new ToolPolicy(
                "DANGEROUS_WRITE", "monitor:logininfor:remove", "清空全部登录日志；该动作不可逆",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_monitor_online_force_logout", new ToolPolicy(
                "DANGEROUS_WRITE", "monitor:online:forceLogout", "强制指定在线会话退出登录",
                objectSchema(Map.of("tokenId", Map.of("type", "string", "description", "在线会话 Token ID")), List.of("tokenId"))));
        allowlist.put("page_monitor_operlog_view", new ToolPolicy(
                "READ", "monitor:operlog:query", "查看指定操作日志详情",
                objectSchema(Map.of("operId", Map.of("type", "integer", "description", "操作日志ID")), List.of("operId"))));
        allowlist.put("page_monitor_operlog_clean", new ToolPolicy(
                "DANGEROUS_WRITE", "monitor:operlog:remove", "清空全部操作日志；该动作不可逆",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_system_user_search", new ToolPolicy(
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
        allowlist.put("page_system_user_edit_open", new ToolPolicy(
                "UI", "system:user:edit", "在当前用户管理页打开指定 userId 的用户编辑弹窗",
                objectSchema(Map.of("userId", Map.of("type", "integer", "description", "用户ID")), List.of("userId"))));
        allowlist.put("page_system_user_edit_set_fields", new ToolPolicy(
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
        allowlist.put("page_system_user_edit_submit", new ToolPolicy(
                "WRITE", "system:user:edit", "提交当前用户编辑表单并真实写入系统",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_system_role_add_submit", new ToolPolicy(
                "DANGEROUS_WRITE", "system:role:add", "创建新角色并写入角色权限配置",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_system_role_edit_submit", new ToolPolicy(
                "DANGEROUS_WRITE", "system:role:edit", "保存角色配置修改；可能影响系统权限边界",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_system_user_auth_role_view", new ToolPolicy(
                "READ", "system:user:query", "查看指定用户当前可分配角色及已授权角色",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_system_user_auth_role_select", new ToolPolicy(
                "UI", "system:user:edit", "在分配角色页面选择准备授予用户的角色，但不保存",
                objectSchema(Map.of(
                        "roleIds", Map.of("type", "array", "items", Map.of("type", "integer"),
                                "description", "准备授予用户的角色ID列表")),
                        List.of("roleIds"))));
        allowlist.put("page_system_user_auth_role_submit", new ToolPolicy(
                "DANGEROUS_WRITE", "system:user:edit", "提交用户角色授权；会改变该用户实际权限",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_system_role_data_scope_open", new ToolPolicy(
                "UI", "system:role:edit", "打开指定角色的数据权限配置",
                objectSchema(Map.of("roleId", Map.of("type", "integer", "description", "角色ID")), List.of("roleId"))));
        allowlist.put("page_system_role_data_scope_set_fields", new ToolPolicy(
                "UI", "system:role:edit", "修改当前角色的数据范围和自定义部门选择但不保存",
                objectSchema(Map.of(
                        "dataScope", Map.of("type", "string", "enum", List.of("1", "2", "3", "4", "5")),
                        "deptIds", Map.of("type", "array", "items", Map.of("type", "integer")),
                        "deptCheckStrictly", Map.of("type", "boolean")), List.of())));
        allowlist.put("page_system_role_data_scope_submit", new ToolPolicy(
                "DANGEROUS_WRITE", "system:role:edit", "提交角色数据权限；会改变该角色可访问的数据范围",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_system_role_auth_user_search", new ToolPolicy(
                "READ", "system:role:list", "查询指定角色当前已授权用户",
                objectSchema(Map.of(
                        "userName", Map.of("type", "string"),
                        "phonenumber", Map.of("type", "string"),
                        "pageNum", Map.of("type", "integer"),
                        "pageSize", Map.of("type", "integer")), List.of())));
        allowlist.put("page_system_role_auth_user_assign_users", new ToolPolicy(
                "DANGEROUS_WRITE", "system:role:edit", "向当前角色批量授权用户",
                objectSchema(Map.of("userIds", Map.of("type", "array", "items", Map.of("type", "integer"))), List.of("userIds"))));
        allowlist.put("page_system_role_auth_user_cancel_user", new ToolPolicy(
                "DANGEROUS_WRITE", "system:role:edit", "取消单个用户的当前角色授权",
                objectSchema(Map.of("userId", Map.of("type", "integer")), List.of("userId"))));
        allowlist.put("page_system_role_auth_user_cancel_users", new ToolPolicy(
                "DANGEROUS_WRITE", "system:role:edit", "批量取消用户的当前角色授权",
                objectSchema(Map.of("userIds", Map.of("type", "array", "items", Map.of("type", "integer"))), List.of("userIds"))));
        allowlist.put("page_system_dept_sort_submit", new ToolPolicy(
                "WRITE", "system:dept:edit", "保存部门显示排序",
                objectSchema(Map.of("items", Map.of("type", "array", "items", Map.of("type", "object"))), List.of("items"))));
        allowlist.put("page_system_notice_read_users", new ToolPolicy(
                "READ", "system:notice:list", "打开公告已读用户并返回当前已读用户列表",
                objectSchema(Map.of("noticeId", Map.of("type", "integer")), List.of("noticeId"))));
        allowlist.put("page_tool_gen_preview", new ToolPolicy(
                "READ", "tool:gen:preview", "预览指定生成表的代码",
                objectSchema(Map.of("tableId", Map.of("type", "integer")), List.of("tableId"))));
        allowlist.put("page_tool_gen_sync_db", new ToolPolicy(
                "DANGEROUS_WRITE", "tool:gen:edit", "将生成配置与数据库表结构强制同步",
                objectSchema(Map.of("tableId", Map.of("type", "integer")), List.of("tableId"))));
        allowlist.put("page_tool_gen_generate", new ToolPolicy(
                "DANGEROUS_WRITE", "tool:gen:code", "按当前生成配置生成代码；自定义路径模式可能写入服务器文件",
                objectSchema(Map.of("tableId", Map.of("type", "integer")), List.of("tableId"))));
        allowlist.put("page_system_profile_view", new ToolPolicy(
                "READ", null, "查看当前登录用户自己的个人资料",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_system_profile_set_fields", new ToolPolicy(
                "UI", null, "修改当前登录用户自己的基本资料表单但不保存",
                objectSchema(Map.of(
                        "nickName", Map.of("type", "string"),
                        "phonenumber", Map.of("type", "string"),
                        "email", Map.of("type", "string"),
                        "sex", Map.of("type", "string", "enum", List.of("0", "1", "2"))), List.of())));
        allowlist.put("page_system_profile_submit", new ToolPolicy(
                "WRITE", null, "保存当前登录用户自己的基本资料",
                objectSchema(Map.of(), List.of())));
        allowlist.put("page_system_profile_select_tab", new ToolPolicy(
                "UI", null, "切换个人中心的基本资料或修改密码页签；密码字段不向 AI 暴露",
                objectSchema(Map.of("tab", Map.of("type", "string", "enum", List.of("userinfo", "resetPwd"))), List.of("tab"))));
    }

    public List<ApprovedTool> approve(List<AiFrontendToolDefinition> requested)
    {
        if (requested == null || requested.isEmpty())
        {
            return List.of();
        }
        List<ApprovedTool> approved = new ArrayList<>();
        for (AiFrontendToolDefinition candidate : requested)
        {
            if (candidate == null || candidate.getName() == null)
            {
                continue;
            }
            ToolPolicy policy = resolvePolicy(candidate.getName());
            if (policy == null || !hasPermission(policy.requiredPermission()))
            {
                continue;
            }
            // The browser only advertises availability. Description, schema, risk and permission
            // always come from this server-side allowlist, so a modified client cannot inject
            // model instructions or expand writable fields.
            approved.add(new ApprovedTool(candidate.getName(), policy.description(), policy.inputSchema(),
                    policy.riskLevel(), policy.requiredPermission()));
        }
        return approved;
    }

    private boolean hasPermission(String permission)
    {
        return permission == null || permission.isBlank() || permissionService.hasPermi(permission);
    }

    public ToolPolicy requirePolicy(String toolName)
    {
        ToolPolicy policy = resolvePolicy(toolName);
        if (policy == null)
        {
            throw new IllegalArgumentException("Unsupported frontend tool: " + toolName);
        }
        return policy;
    }

    private ToolPolicy resolvePolicy(String toolName)
    {
        ToolPolicy exact = allowlist.get(toolName);
        if (exact != null)
        {
            return exact;
        }
        return deriveCrudPolicy(toolName);
    }

    private ToolPolicy deriveCrudPolicy(String toolName)
    {
        if (toolName == null || !toolName.startsWith("page_"))
        {
            return null;
        }

        String[] actionSuffixes = {
                "reset_password", "change_status", "add_set_fields", "edit_set_fields",
                "add_submit", "edit_submit", "import_open", "refresh_cache", "auth_role", "auth_user",
                "add_open", "edit_open", "search", "reset", "delete", "export", "view", "clean"
        };

        String action = null;
        String resourcePart = null;
        for (String suffix : actionSuffixes)
        {
            String marker = "_" + suffix;
            if (toolName.endsWith(marker))
            {
                action = suffix;
                resourcePart = toolName.substring("page_".length(), toolName.length() - marker.length());
                break;
            }
        }
        if (action == null || resourcePart == null)
        {
            return null;
        }

        int split = resourcePart.indexOf('_');
        if (split <= 0 || split >= resourcePart.length() - 1)
        {
            return null;
        }
        String module = resourcePart.substring(0, split);
        String resource = resourcePart.substring(split + 1);
        if (!module.matches("[a-zA-Z0-9]+") || !resource.matches("[a-zA-Z0-9_]+"))
        {
            return null;
        }

        String permissionAction;
        String risk;
        switch (action)
        {
            case "search", "reset", "view" -> {
                permissionAction = "list";
                risk = "READ";
            }
            case "add_open", "add_set_fields" -> {
                permissionAction = "add";
                risk = "UI";
            }
            case "add_submit" -> {
                permissionAction = "add";
                risk = "WRITE";
            }
            case "edit_open", "edit_set_fields", "auth_role", "auth_user" -> {
                permissionAction = "edit";
                risk = "UI";
            }
            case "edit_submit", "change_status" -> {
                permissionAction = "edit";
                risk = "WRITE";
            }
            case "refresh_cache" -> {
                permissionAction = "remove";
                risk = "WRITE";
            }
            case "delete", "clean" -> {
                permissionAction = "remove";
                risk = "DANGEROUS_WRITE";
            }
            case "reset_password" -> {
                permissionAction = "resetPwd";
                risk = "DANGEROUS_WRITE";
            }
            case "export" -> {
                permissionAction = "export";
                risk = "READ";
            }
            case "import_open" -> {
                permissionAction = "import";
                risk = "UI";
            }
            default -> {
                return null;
            }
        }

        String permissionResource = switch (resource)
        {
            case "role_auth_user" -> "role";
            case "dict_data" -> "dict";
            case "job_log" -> "job";
            case "gen_edit" -> "gen";
            default -> resource.replace('_', ':');
        };
        String permission = module + ":" + permissionResource + ":" + permissionAction;
        String description = "执行当前页面已注册的语义化业务动作 " + action
                + "；字段、记录和动作范围以当前 Page Context 为准，不能越过 RuoYi 后端权限与数据范围";
        return new ToolPolicy(risk, permission, description, flexibleObjectSchema());
    }

    private static Map<String, Object> flexibleObjectSchema()
    {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return Map.copyOf(schema);
    }

    private static Map<String, Object> objectSchema(Map<String, ?> properties, List<String> required)
    {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty())
        {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    public record ApprovedTool(String name, String description, Map<String, Object> inputSchema, String riskLevel,
            String requiredPermission)
    {
    }

    public record ToolPolicy(String riskLevel, String requiredPermission, String description,
            Map<String, Object> inputSchema)
    {
        public String defaultDescription()
        {
            return description;
        }
    }
}
