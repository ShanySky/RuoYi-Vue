package com.ruoyi.ai.tool;

import java.util.List;
import org.springframework.stereotype.Component;
import static com.ruoyi.ai.tool.ToolPolicyDefinition.flexibleObjectSchema;

@Component
public class GenericCrudToolPolicyProvider implements ToolPolicyProvider
{
    private static final List<String> ACTION_SUFFIXES = List.of(
            "reset_password", "change_status", "add_set_fields", "edit_set_fields",
            "add_submit", "edit_submit", "import_open", "refresh_cache", "auth_role", "auth_user",
            "add_open", "edit_open", "search", "reset", "delete", "export", "view", "clean");

    @Override
    public ToolPolicyDefinition resolve(String toolName)
    {
        if (toolName == null || !toolName.startsWith("page_"))
        {
            return null;
        }

        String action = null;
        String resourcePart = null;
        for (String suffix : ACTION_SUFFIXES)
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
        return new ToolPolicyDefinition(risk, permission, description, flexibleObjectSchema());
    }
}
