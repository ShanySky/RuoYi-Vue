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
        allowlist.put("page_system_user_search", new ToolPolicy(
                "READ", "system:user:list", "在当前用户管理页设置查询条件并查询用户",
                objectSchema(Map.of(
                        "userName", Map.of("type", "string", "description", "用户账号关键字"),
                        "phonenumber", Map.of("type", "string", "description", "手机号码"),
                        "status", Map.of("type", "string", "enum", List.of("0", "1"), "description", "0正常，1停用")),
                        List.of())));
        allowlist.put("page_system_user_edit_open", new ToolPolicy(
                "UI", "system:user:edit", "在当前用户管理页打开指定 userId 的用户编辑弹窗",
                objectSchema(Map.of("userId", Map.of("type", "integer", "description", "用户ID")), List.of("userId"))));
        allowlist.put("page_system_user_edit_set_fields", new ToolPolicy(
                "UI", "system:user:edit", "修改当前已打开用户编辑表单的安全字段但不保存；仅支持用户昵称 nickName 等安全字段，不支持修改登录账号 userName",
                objectSchema(Map.of(
                        "nickName", Map.of("type", "string", "description", "用户昵称"),
                        "phonenumber", Map.of("type", "string", "description", "手机号码"),
                        "email", Map.of("type", "string", "description", "邮箱"),
                        "sex", Map.of("type", "string", "enum", List.of("0", "1", "2"), "description", "性别"),
                        "status", Map.of("type", "string", "enum", List.of("0", "1"), "description", "状态"),
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
            ToolPolicy policy = allowlist.get(candidate.getName());
            if (policy == null || !permissionService.hasPermi(policy.requiredPermission()))
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

    public ToolPolicy requirePolicy(String toolName)
    {
        ToolPolicy policy = allowlist.get(toolName);
        if (policy == null)
        {
            throw new IllegalArgumentException("Unsupported frontend tool: " + toolName);
        }
        return policy;
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
