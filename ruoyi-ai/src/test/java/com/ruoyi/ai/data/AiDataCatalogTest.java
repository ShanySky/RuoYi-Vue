package com.ruoyi.ai.data;

import org.junit.jupiter.api.Test;
import com.ruoyi.common.annotation.DataScope;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiDataCatalogTest
{
    @Test void AnnotationPresentationOrderCannotInvalidateAnUnchangedPermissionContract()
    {
        DataScope first = scope("@DataScope(deptAlias=d,userAlias=u)");
        DataScope second = scope("@DataScope(userAlias=u,deptAlias=d)");
        assertNotEquals(first.toString(), second.toString());
        assertEquals(AiDataCatalog.scopeContract(first), AiDataCatalog.scopeContract(second));
        when(second.deptAlias()).thenReturn("other");
        assertNotEquals(AiDataCatalog.scopeContract(first), AiDataCatalog.scopeContract(second));
    }

    private DataScope scope(String representation)
    {
        DataScope scope = mock(DataScope.class);
        when(scope.toString()).thenReturn(representation);
        when(scope.deptAlias()).thenReturn("d");
        when(scope.deptField()).thenReturn("dept_id");
        when(scope.userAlias()).thenReturn("u");
        when(scope.userField()).thenReturn("user_id");
        when(scope.permission()).thenReturn("");
        return scope;
    }
}
