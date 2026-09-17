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
        allowlist.put("page_system_user_search", new ToolPolicy("READ", "system:user:list", "查询当前用户管理页的用户列表"));
        allowlist.put("page_system_user_edit_open", new ToolPolicy("UI", "system:user:edit", "在当前用户管理页打开指定用户的编辑弹窗"));
        allowlist.put("page_system_user_edit_set_fields", new ToolPolicy("UI", "system:user:edit", "修改当前已打开用户编辑表单的安全字段，但不提交"));
        allowlist.put("page_system_user_edit_submit", new ToolPolicy("WRITE", "system:user:edit", "提交当前用户编辑表单并写入系统"));
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
            String description = candidate.getDescription();
            if (description == null || description.isBlank() || description.length() > 500)
            {
                description = policy.defaultDescription();
            }
            Map<String, Object> schema = candidate.getInputSchema();
            if (schema == null || schema.isEmpty())
            {
                schema = Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
            }
            approved.add(new ApprovedTool(candidate.getName(), description, schema, policy.riskLevel(), policy.requiredPermission()));
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

    public record ApprovedTool(String name, String description, Map<String, Object> inputSchema, String riskLevel,
            String requiredPermission)
    {
    }

    public record ToolPolicy(String riskLevel, String requiredPermission, String defaultDescription)
    {
    }
}
