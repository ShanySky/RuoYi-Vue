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
                "add_submit", "edit_submit", "import_open", "auth_role", "auth_user",
                "add_open", "edit_open", "search", "reset", "delete", "export", "view"
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
            case "delete" -> {
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

        String permission = module + ":" + resource.replace('_', ':') + ":" + permissionAction;
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
