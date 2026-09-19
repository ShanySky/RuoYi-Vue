package com.ruoyi.ai.tool;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GenericCrudToolPolicyProviderTest
{
    private final GenericCrudToolPolicyProvider provider = new GenericCrudToolPolicyProvider();

    @Test
    void derivesKnownCrudActionsAndResourceRemaps()
    {
        ToolPolicyDefinition search = provider.resolve("page_system_post_search");
        assertNotNull(search);
        assertEquals("READ", search.riskLevel());
        assertEquals("system:post:list", search.requiredPermission());

        ToolPolicyDefinition roleUsers = provider.resolve("page_system_role_auth_user_view");
        assertNotNull(roleUsers);
        assertEquals("READ", roleUsers.riskLevel());
        assertEquals("system:role:list", roleUsers.requiredPermission());

        ToolPolicyDefinition resetPassword = provider.resolve("page_system_user_reset_password");
        assertEquals("DANGEROUS_WRITE", resetPassword.riskLevel());
        assertEquals("system:user:resetPwd", resetPassword.requiredPermission());
    }

    @Test
    void rejectsUnknownOrMalformedNames()
    {
        assertNull(provider.resolve("page_system_user_execute_anything"));
        assertNull(provider.resolve("page_user_search"));
        assertNull(provider.resolve("server_system_user_search"));
        assertNull(provider.resolve("page_system_user-name_search"));
    }
}
