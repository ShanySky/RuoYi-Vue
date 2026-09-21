package com.ruoyi.ai.data;

import java.sql.Types;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.server.ApiContractSchema;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;

class AiDataQueryTest
{
    private final ObjectMapper json = new ObjectMapper();
    private final AiDataQuery query = new AiDataQuery(new ApiContractSchema());
    private final AiDataAccess.Allowed allowed = new AiDataAccess.Allowed(null, "task", List.of(
        new AiDataCatalog.Field("user_id", "sys_user", "user_id", "number", Types.BIGINT),
        new AiDataCatalog.Field("user_name", "sys_user", "user_name", "string", Types.VARCHAR),
        new AiDataCatalog.Field("dept_name", "sys_dept", "dept_name", "string", Types.VARCHAR)), Set.of("QUERY", "AGGREGATE"), "auth");
    private static final String SOURCE = "select user_id,user_name,dept_name from original_authorized_query";

    @Test void hostileValuesRemainBoundAndNeverBecomeSql() throws Exception
    {
        var result = query.compile(allowed, SOURCE, json.readTree("""
            {"operation":"QUERY","columns":["user_name"],"filters":[{"field":"user_name","operator":"eq","value":"x' OR 1=1; DELETE FROM sys_user --"}]}
            """));
        assertFalse(result.sql().contains("DELETE"));
        assertFalse(result.sql().contains("OR 1=1"));
        assertEquals("x' OR 1=1; DELETE FROM sys_user --", result.parameters().get(0));
        assertTrue(result.sql().contains("from (" + SOURCE + ") ai_authorized"));
    }

    @Test void HiddenFieldCannotInfluenceAnyQueryPosition() throws Exception
    {
        for (String input : List.of(
            "{\"operation\":\"QUERY\",\"columns\":[\"password\"]}",
            "{\"operation\":\"QUERY\",\"columns\":[\"user_name\"],\"filters\":[{\"field\":\"password\",\"operator\":\"eq\",\"value\":\"secret\"}]}",
            "{\"operation\":\"QUERY\",\"columns\":[\"user_name\"],\"orderBy\":[{\"field\":\"password\",\"direction\":\"ASC\"}]}",
            "{\"operation\":\"AGGREGATE\",\"groupBy\":[\"password\"],\"metrics\":[{\"function\":\"COUNT\"}]}",
            "{\"operation\":\"AGGREGATE\",\"metrics\":[{\"function\":\"COUNT\",\"field\":\"password\"}]}"))
            assertThrows(ServiceException.class, () -> query.compile(allowed, SOURCE, json.readTree(input)));
    }

    @Test void UnsupportedSqlAndResourceOverflowAreRejectedBeforeExecution() throws Exception
    {
        for (String input : List.of(
            "{\"operation\":\"DELETE\",\"columns\":[\"user_name\"]}",
            "{\"operation\":\"QUERY\",\"columns\":[\"user_name\"],\"sql\":\"select * from sys_user\"}",
            "{\"operation\":\"QUERY\",\"columns\":[\"user_name\"],\"limit\":101}",
            "{\"operation\":\"QUERY\",\"columns\":[\"user_name\"],\"offset\":10001}",
            "{\"operation\":\"QUERY\",\"columns\":[]}",
            "{\"operation\":\"QUERY\",\"columns\":[\"user_id\"],\"filters\":[{\"field\":\"user_id\",\"operator\":\"eq\",\"value\":\"0 OR 1=1\"}]}"))
            assertThrows(ServiceException.class, () -> query.compile(allowed, SOURCE, json.readTree(input)));
        var tooMany = json.readTree("{\"operation\":\"QUERY\",\"columns\":[\"user_name\"],\"filters\":[]}");
        for (int index = 0; index < 11; index++) ((com.fasterxml.jackson.databind.node.ArrayNode) tooMany.get("filters"))
            .add(json.readTree("{\"field\":\"user_name\",\"operator\":\"is_null\"}"));
        assertThrows(ServiceException.class, () -> query.compile(allowed, SOURCE, tooMany));
    }

    @Test void AggregationAndOperationPolicyCannotBypassTheAuthorizedView() throws Exception
    {
        var input = json.readTree("{\"operation\":\"AGGREGATE\",\"groupBy\":[\"dept_name\"],\"metrics\":[{\"function\":\"COUNT\"}],\"orderBy\":[{\"field\":\"metric_1\",\"direction\":\"DESC\"}]}");
        var result = query.compile(allowed, SOURCE, input);
        assertEquals(List.of("dept_name", "metric_1"), result.columns());
        assertTrue(result.sql().contains("from (" + SOURCE + ") ai_authorized group by `dept_name`"));
        var queryOnly = new AiDataAccess.Allowed(null, "task", allowed.fields(), Set.of("QUERY"), "auth");
        assertThrows(ServiceException.class, () -> query.compile(queryOnly, SOURCE, input));
    }

    @Test void RealModelEmptyUnusedArraysDoNotWeakenFieldOrOperationValidation() throws Exception
    {
        var aggregate = json.readTree("{\"operation\":\"AGGREGATE\",\"columns\":[],\"groupBy\":[\"dept_name\"],\"metrics\":[{\"function\":\"COUNT\",\"field\":\"user_id\"}]}");
        assertEquals(List.of("dept_name", "metric_1"), query.compile(allowed, SOURCE, aggregate).columns());
        var detail = json.readTree("{\"operation\":\"QUERY\",\"columns\":[\"user_name\"],\"groupBy\":[],\"metrics\":[]}");
        assertEquals(List.of("user_name"), query.compile(allowed, SOURCE, detail).columns());
        ((com.fasterxml.jackson.databind.node.ArrayNode) aggregate.get("columns")).add("password");
        assertThrows(ServiceException.class, () -> query.compile(allowed, SOURCE, aggregate));
        ((com.fasterxml.jackson.databind.node.ArrayNode) aggregate.get("columns")).removeAll().add("user_name");
        assertThrows(ServiceException.class, () -> query.compile(allowed, SOURCE, aggregate));
    }
}
