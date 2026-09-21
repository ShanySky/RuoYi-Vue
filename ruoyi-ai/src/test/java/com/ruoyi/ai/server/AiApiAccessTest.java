package com.ruoyi.ai.server;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.ai.mapper.AiApiPolicyMapper;
import com.ruoyi.ai.mapper.AiApiPolicyMapper.Policy;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiApiAccessTest
{
    private final AiApiCatalog catalog = mock(AiApiCatalog.class);
    private final AiApiPolicyMapper policies = mock(AiApiPolicyMapper.class);
    private final AiFreshIdentity identity = mock(AiFreshIdentity.class);
    private final AiApiAccess access = new AiApiAccess(catalog, policies, identity);

    @Test
    void aNewContractIsClosedUntilExplicitlyOpenedAndClosingTakesEffect()
    {
        setup("v1");
        assertTrue(access.visible().isEmpty());
        assertThrows(ServiceException.class, () -> access.require("test-api"));
        policy(true, "v1");
        assertEquals(1, access.visible().size());
        assertEquals("test-api", access.require("test-api").id());
        policy(false, "v1");
        assertTrue(access.visible().isEmpty());
        assertThrows(ServiceException.class, () -> access.require("test-api"));
    }

    @Test
    void contractChangeInvalidatesDiscoveryDetailsAndStaleGovernanceSubmission()
    {
        setup("v2");
        policy(true, "v1");
        assertTrue(access.visible().isEmpty());
        assertThrows(ServiceException.class, () -> access.require("test-api"));
        assertEquals(true, access.governance().get(0).get("contractChanged"));
        assertThrows(ServiceException.class, () -> access.configure("test-api", "v1", true));
        verify(policies, never()).save(anyString(), anyString(), anyBoolean(), anyString());
    }

    private void setup(String fingerprint)
    {
        LoginUser user = new LoginUser();
        user.setPermissions(Set.of("system:post:list"));
        when(identity.refresh()).thenReturn(user);
        var capability = new AiApiCatalog.Capability("test-api", "system", "post", "list", "岗位",
                "GET", "/system/post/list", "system:post:list", "READ", Map.of(), fingerprint, "", true);
        when(catalog.all()).thenReturn(List.of(capability));
        when(catalog.require("test-api")).thenReturn(capability);
    }

    private void policy(boolean enabled, String fingerprint)
    {
        Policy saved = new Policy("test-api", fingerprint, enabled, 1L);
        when(policies.all()).thenReturn(List.of(saved));
        when(policies.get("test-api")).thenReturn(saved);
    }
}
