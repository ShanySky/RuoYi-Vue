package com.ruoyi.ai.data;

import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.mapper.AiDataPolicyMapper;
import com.ruoyi.ai.server.AiApiCatalog;
import com.ruoyi.ai.server.AiFreshIdentity;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiDataAccessTest
{
    private final AiDataCatalog catalog = mock(AiDataCatalog.class);
    private final AiDataPolicyMapper policies = mock(AiDataPolicyMapper.class);
    private final AiFreshIdentity identity = mock(AiFreshIdentity.class);
    private final AiDataAccess access = new AiDataAccess(catalog, policies, identity, new ObjectMapper());

    private LoginUser setup()
    {
        var api = new AiApiCatalog.Capability("api_user", "system", "user", "list", "用户", "GET", "/system/user/list", "system:user:list", "READ", Map.of(), "api-v1", "", true);
        var binding = new AiDataCatalog.Binding("data_users", "用户", "/system/user/list", "original", Object.class, "list", SysUser::new, true, Set.of("sys_user"));
        var fields = List.of(new AiDataCatalog.Field("user_name", "sys_user", "user_name", "string", Types.VARCHAR));
        var view = new AiDataCatalog.View(binding, api, fields, "view-v1");
        var table = new AiDataCatalog.Table("sys_user", "用户", List.of(new AiDataCatalog.Column("user_name", "varchar", "用户名", true)), "table-v1", true);
        when(catalog.snapshot()).thenReturn(new AiDataCatalog.Snapshot("task", "db-v1", List.of(table), List.of(view)));
        LoginUser user = new LoginUser();
        user.setUser(new SysUser(2L));
        user.setPermissions(Set.of("system:user:list"));
        when(identity.refresh()).thenReturn(user);
        when(identity.fingerprint(user)).thenReturn("current-scope");
        return user;
    }

    private void open()
    {
        when(policies.get("database")).thenReturn(new AiDataPolicyMapper.Policy("database", "db-v1", true, "[]", "[]", 1));
        when(policies.get("sys_user")).thenReturn(new AiDataPolicyMapper.Policy("sys_user", "table-v1", true, "[\"user_name\"]", "[\"QUERY\",\"AGGREGATE\"]", 1));
    }

    @Test void OpeningDatabaseNeverGrantsTheUsersMissingBusinessPermission()
    {
        LoginUser user = setup();
        assertTrue(access.visible().isEmpty());
        open();
        assertEquals(1, access.visible().size());
        user.setPermissions(Set.of("ai:data:edit"));
        assertTrue(access.visible().isEmpty());
        assertThrows(ServiceException.class, () -> access.require("data_users"));
    }

    @Test void TableClosureSchemaChangeAndRemovedFieldsCloseEveryDisclosure()
    {
        setup();
        open();
        String original = access.require("data_users").authorization();
        when(policies.get("sys_user")).thenReturn(new AiDataPolicyMapper.Policy("sys_user", "table-v1", true, "[\"user_name\"]", "[\"QUERY\"]", 2));
        assertNotEquals(original, access.require("data_users").authorization());
        assertEquals(Set.of("QUERY"), access.require("data_users").operations());
        for (var policy : List.of(
            new AiDataPolicyMapper.Policy("sys_user", "table-v1", false, "[\"user_name\"]", "[\"QUERY\"]", 3),
            new AiDataPolicyMapper.Policy("sys_user", "old-contract", true, "[\"user_name\"]", "[\"QUERY\"]", 4),
            new AiDataPolicyMapper.Policy("sys_user", "table-v1", true, "[]", "[\"QUERY\"]", 5)))
        {
            when(policies.get("sys_user")).thenReturn(policy);
            assertTrue(access.visible().isEmpty());
            assertThrows(ServiceException.class, () -> access.require("data_users"));
        }
    }
}
