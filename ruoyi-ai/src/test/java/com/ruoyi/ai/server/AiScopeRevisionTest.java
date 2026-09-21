package com.ruoyi.ai.server;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.mapper.AiAuthorizationMapper;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.system.service.ISysUserService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiScopeRevisionTest
{
    private final AiAuthorizationMapper scope = mock(AiAuthorizationMapper.class);
    private final AiFreshIdentity identity = new AiFreshIdentity(mock(ISysUserService.class),
            mock(SysPermissionService.class), mock(RedisCache.class), scope, new ObjectMapper());

    @Test
    void ownershipChangeInvalidatesTheSameUsersAuthorizationAndMissingProtectionClosesAccess()
    {
        LoginUser user = user(2L);
        when(scope.scopeRevision()).thenReturn(1L);
        String previous = identity.fingerprint(user);
        when(scope.scopeRevision()).thenReturn(2L);
        assertNotEquals(previous, identity.fingerprint(user));
        when(scope.scopeRevision()).thenReturn(null);
        assertThrows(ServiceException.class, () -> identity.fingerprint(user));
    }

    @Test
    void superAdministratorPreservesOriginalUnrestrictedScopeSemantics()
    {
        String previous = identity.fingerprint(user(1L));
        when(scope.scopeRevision()).thenReturn(999L);
        assertEquals(previous, identity.fingerprint(user(1L)));
        verify(scope, never()).scopeRevision();
    }

    private LoginUser user(Long id)
    {
        SysUser entity = new SysUser();
        entity.setUserId(id);
        entity.setDeptId(103L);
        entity.setRoles(List.of());
        return new LoginUser(id, 103L, entity, Set.of("system:user:list"));
    }
}
