package com.ruoyi.ai.tool;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolPolicyRegistryTest
{
    private final GenericCrudToolPolicyProvider generic = new GenericCrudToolPolicyProvider();
    private final ToolPolicyRegistry registry = new ToolPolicyRegistry(
            new SystemToolPolicyProvider(), new MonitorToolPolicyProvider(),
            new GeneratorToolPolicyProvider(), generic);

    @Test
    void explicitPolicyWinsBeforeGenericCrud()
    {
        ToolPolicyDefinition explicit = registry.require("page_system_user_reset_password");
        ToolPolicyDefinition derived = generic.resolve("page_system_user_reset_password");
        assertEquals("DANGEROUS_WRITE", explicit.riskLevel());
        assertEquals("DANGEROUS_WRITE", derived.riskLevel());
        assertEquals(Boolean.FALSE, explicit.inputSchema().get("additionalProperties"));
        assertEquals(Boolean.TRUE, derived.inputSchema().get("additionalProperties"));
        assertTrue(((Map<?, ?>) explicit.inputSchema().get("properties")).containsKey("password"));
    }

    @Test
    void userEditFieldsCannotExposeLoginNameOrPassword()
    {
        ToolPolicyDefinition policy = registry.require("page_system_user_edit_set_fields");
        Map<?, ?> properties = (Map<?, ?>) policy.inputSchema().get("properties");
        assertFalse(properties.containsKey("userName"));
        assertFalse(properties.containsKey("password"));
        assertTrue(properties.containsKey("nickName"));
    }
}
