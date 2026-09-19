package com.ruoyi.ai.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.ruoyi.ai.dto.AiFrontendToolDefinition;
import com.ruoyi.framework.web.service.PermissionService;

@Service
public class AiFrontendToolPolicy
{
    private final PermissionService permissionService;
    private final ToolPolicyRegistry registry;

    public AiFrontendToolPolicy(PermissionService permissionService, ToolPolicyRegistry registry)
    {
        this.permissionService = permissionService;
        this.registry = registry;
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
            ToolPolicyDefinition policy = registry.resolve(candidate.getName());
            if (policy == null || !hasPermission(policy.requiredPermission()))
            {
                continue;
            }
            // Browser data is availability only. Trusted description/schema/risk/permission
            // always come from server-side providers.
            approved.add(new ApprovedTool(candidate.getName(), policy.description(), policy.inputSchema(),
                    policy.riskLevel(), policy.requiredPermission()));
        }
        return approved;
    }

    public ToolPolicyDefinition requirePolicy(String toolName)
    {
        return registry.require(toolName);
    }

    private boolean hasPermission(String permission)
    {
        return permission == null || permission.isBlank() || permissionService.hasPermi(permission);
    }

    public record ApprovedTool(String name, String description, Map<String, Object> inputSchema, String riskLevel,
            String requiredPermission)
    {
    }
}
