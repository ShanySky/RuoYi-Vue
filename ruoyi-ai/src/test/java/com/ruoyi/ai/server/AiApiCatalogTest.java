package com.ruoyi.ai.server;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysPost;
import static org.junit.jupiter.api.Assertions.*;

class AiApiCatalogTest
{
    private final ApiContractSchema schemas = new ApiContractSchema();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void discoversNewStandardMappingWithoutAiRegistrationAndReactsToRemoval() throws Exception
    {
        RequestMappingHandlerMapping mappings = new RequestMappingHandlerMapping();
        AiApiCatalog catalog = new AiApiCatalog(mappings, schemas);
        assertTrue(catalog.all().isEmpty());
        RequestMappingInfo route = RequestMappingInfo.paths("/system/post/list").methods(RequestMethod.GET).build();
        mappings.registerMapping(route, new BusinessController(), BusinessController.class.getMethod("list", SysPost.class));
        var capability = catalog.all().get(0);
        assertEquals("system", capability.module());
        assertEquals("post", capability.object());
        assertEquals("system:post:list", capability.permission());
        assertTrue(capability.supported());
        LoginUser user = new LoginUser();
        user.setPermissions(Set.of("system:post:query"));
        assertFalse(capability.granted(user));
        user.setPermissions(Set.of("system:*"));
        assertFalse(capability.granted(user));
        user.setPermissions(Set.of("system:post:list"));
        assertTrue(capability.granted(user));
        mappings.unregisterMapping(route);
        assertThrows(ServiceException.class, () -> catalog.require(capability.id()));
    }

    @Test
    void derivesRequiredFieldsAndRejectsUnknownArgumentsAndOversizedPage() throws Exception
    {
        Method add = BusinessController.class.getMethod("add", SysPost.class);
        Map<String, Object> schema = schemas.describe(new org.springframework.web.method.HandlerMethod(new BusinessController(), add));
        schemas.validate(schema, json.readTree("{\"body\":{\"postCode\":\"engineer\",\"postName\":\"工程师\",\"postSort\":1}}"));
        assertThrows(ServiceException.class, () -> schemas.validate(schema, json.readTree("{\"body\":{\"postCode\":\"engineer\"}}")));
        assertThrows(ServiceException.class, () -> schemas.validate(schema,
                json.readTree("{\"body\":{\"postCode\":\"a\",\"postName\":\"b\",\"postSort\":1,\"params\":{\"dataScope\":\"1=1\"}}}")));
        Method list = BusinessController.class.getMethod("list", SysPost.class);
        Map<String, Object> query = schemas.describe(new org.springframework.web.method.HandlerMethod(new BusinessController(), list));
        schemas.validate(query, json.readTree("{\"query\":{\"pageSize\":50}}"));
        assertThrows(ServiceException.class, () -> schemas.validate(query, json.readTree("{\"query\":{\"pageSize\":51}}")));
    }

    @Test
    void unsupportedAuthorizationAndAiControlPlaneCannotBeOpened() throws Exception
    {
        RequestMappingHandlerMapping mappings = new RequestMappingHandlerMapping();
        mappings.registerMapping(RequestMappingInfo.paths("/system/noGuard").methods(RequestMethod.GET).build(),
                new BusinessController(), BusinessController.class.getMethod("unguarded"));
        mappings.registerMapping(RequestMappingInfo.paths("/ai/admin/service").methods(RequestMethod.POST).build(),
                new BusinessController(), BusinessController.class.getMethod("add", SysPost.class));
        mappings.registerMapping(RequestMappingInfo.paths("/monitor/job/run").methods(RequestMethod.POST).build(),
                new BusinessController(), BusinessController.class.getMethod("add", SysPost.class));
        mappings.registerMapping(RequestMappingInfo.paths("/system/ambiguous").methods(RequestMethod.GET).build(),
                new BusinessController(), BusinessController.class.getMethod("ambiguous"));
        List<AiApiCatalog.Capability> capabilities = new AiApiCatalog(mappings, schemas).all();
        LoginUser administrator = new LoginUser();
        administrator.setPermissions(Set.of("*:*:*"));
        assertTrue(capabilities.stream().noneMatch(AiApiCatalog.Capability::supported));
        assertTrue(capabilities.stream().noneMatch(capability -> capability.granted(administrator)));
    }

    @Test
    void mandatoryUnspecifiedCollectionRejectsTheContractInsteadOfDroppingARequiredField() throws Exception
    {
        Method method = BusinessController.class.getMethod("complex", RequiredCollection.class);
        assertThrows(IllegalArgumentException.class, () -> schemas.describe(
                new org.springframework.web.method.HandlerMethod(new BusinessController(), method)));
    }

    public static class RequiredCollection
    {
        private List<Object> entries;
        @jakarta.validation.constraints.NotEmpty
        public List<Object> getEntries() { return entries; }
        public void setEntries(List<Object> entries) { this.entries = entries; }
    }

    static class BusinessController
    {
        @GetMapping("/list")
        @PreAuthorize("@ss.hasPermi('system:post:list')")
        public TableDataInfo list(SysPost post) { return new TableDataInfo(); }

        @PreAuthorize("@ss.hasPermi('system:post:add')")
        public AjaxResult add(@Validated @RequestBody SysPost post) { return AjaxResult.success(); }

        public AjaxResult complex(@Validated @RequestBody RequiredCollection body) { return AjaxResult.success(); }

        public AjaxResult unguarded() { return AjaxResult.success(); }

        @PreAuthorize("@ss.hasPermi('system:post:list,system:user:list')")
        public AjaxResult ambiguous() { return AjaxResult.success(); }
    }
}
