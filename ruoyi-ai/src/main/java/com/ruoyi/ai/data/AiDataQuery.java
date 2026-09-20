package com.ruoyi.ai.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.ai.data.AiDataAccess.Allowed;
import com.ruoyi.ai.data.AiDataCatalog.Field;
import com.ruoyi.ai.server.ApiContractSchema;
import com.ruoyi.common.exception.ServiceException;

/** 模型只选择字段与有限运算；表、范围和 SQL 表达式由服务端掌握。 */
@Component
public class AiDataQuery
{
    public record Compiled(String sql, List<Object> parameters, int limit, int offset, List<String> columns) { }
    private final ApiContractSchema validation;

    public AiDataQuery(ApiContractSchema validation) { this.validation = validation; }

    public Map<String, Object> schema(Allowed allowed)
    {
        var schema = ApiContractSchema.object();
        List<String> names = allowed.fields().stream().map(Field::name).toList();
        Map<String, Object> field = Map.of("type", "string", "enum", names);
        ApiContractSchema.add(schema, "operation", Map.of("type", "string", "enum", allowed.operations().stream().sorted().toList()), true);
        ApiContractSchema.add(schema, "columns", array(field, 0, 12), false);
        ApiContractSchema.add(schema, "groupBy", array(field, 0, 4), false);
        var metric = ApiContractSchema.object();
        ApiContractSchema.add(metric, "function", Map.of("type", "string", "enum", List.of("COUNT", "SUM", "AVG", "MIN", "MAX")), true);
        ApiContractSchema.add(metric, "field", field, false);
        ApiContractSchema.add(schema, "metrics", array(metric, 0, 6), false);
        var filter = ApiContractSchema.object();
        ApiContractSchema.add(filter, "field", field, true);
        ApiContractSchema.add(filter, "operator", Map.of("type", "string", "enum", List.of("eq", "ne", "gt", "gte", "lt", "lte", "contains", "in", "is_null", "not_null")), true);
        Map<String, Object> value = Map.of("type", "string", "maxLength", 256, "description", "值均用字符串；数值字段会严格转为数值");
        ApiContractSchema.add(filter, "value", value, false);
        ApiContractSchema.add(filter, "values", array(value, 1, 20), false);
        ApiContractSchema.add(schema, "filters", array(filter, 0, 10), false);
        var order = ApiContractSchema.object();
        List<String> sortable = new ArrayList<>(names);
        for (int index = 1; index <= 6; index++) sortable.add("metric_" + index);
        ApiContractSchema.add(order, "field", Map.of("type", "string", "enum", sortable), true);
        ApiContractSchema.add(order, "direction", Map.of("type", "string", "enum", List.of("ASC", "DESC")), true);
        ApiContractSchema.add(schema, "orderBy", array(order, 0, 3), false);
        ApiContractSchema.add(schema, "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100), false);
        ApiContractSchema.add(schema, "offset", Map.of("type", "integer", "minimum", 0, "maximum", 10000), false);
        return schema;
    }

    public Compiled compile(Allowed allowed, String authorizedSql, JsonNode input)
    {
        validation.validate(schema(allowed), input);
        Map<String, Field> fields = allowed.fields().stream().collect(Collectors.toMap(Field::name, field -> field));
        List<String> output = new ArrayList<>(), select = new ArrayList<>(), group = new ArrayList<>();
        String operation = input.path("operation").asText();
        if ("QUERY".equals(operation))
        {
            if (input.path("columns").isEmpty() || !input.path("metrics").isEmpty() || !input.path("groupBy").isEmpty()) throw invalid();
            for (JsonNode field : input.path("columns"))
            {
                output.add(field.asText());
                select.add(quote(field.asText()));
            }
        }
        else
        {
            if (input.path("metrics").isEmpty() || !input.path("columns").isEmpty()) throw invalid();
            for (JsonNode field : input.path("groupBy"))
            {
                output.add(field.asText());
                group.add(quote(field.asText()));
                select.add(quote(field.asText()));
            }
            int index = 0;
            for (JsonNode metric : input.path("metrics"))
            {
                String function = metric.path("function").asText();
                String field = metric.path("field").asText("");
                if (!"COUNT".equals(function) && (field.isEmpty() || !"number".equals(fields.get(field).type()))) throw invalid();
                String alias = "metric_" + (++index);
                output.add(alias);
                select.add(function + "(" + (field.isEmpty() ? "*" : quote(field)) + ") as " + quote(alias));
            }
        }
        if (output.isEmpty() || new LinkedHashSet<>(output).size() != output.size()) throw invalid();
        StringBuilder sql = new StringBuilder("select /*+ MAX_EXECUTION_TIME(1500) */ ")
                .append(String.join(",", select)).append(" from (").append(authorizedSql).append(") ai_authorized");
        List<Object> parameters = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        for (JsonNode filter : input.path("filters"))
        {
            Field field = fields.get(filter.path("field").asText());
            String column = quote(field.name()), operator = filter.path("operator").asText();
            if (Set.of("is_null", "not_null").contains(operator))
            {
                if (filter.has("value") || filter.has("values")) throw invalid();
                conditions.add(column + ("is_null".equals(operator) ? " is null" : " is not null"));
            }
            else if ("in".equals(operator))
            {
                if (!filter.has("values") || filter.has("value")) throw invalid();
                List<String> placeholders = new ArrayList<>();
                for (JsonNode value : filter.path("values"))
                {
                    placeholders.add("?");
                    parameters.add(value(field, value.asText()));
                }
                conditions.add(column + " in (" + String.join(",", placeholders) + ")");
            }
            else
            {
                if (!filter.has("value") || filter.has("values")) throw invalid();
                String value = filter.path("value").asText();
                if ("contains".equals(operator))
                {
                    if (!"string".equals(field.type())) throw invalid();
                    conditions.add(column + " like ? escape '!'");
                    parameters.add("%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
                }
                else
                {
                    String comparator = Map.of("eq", "=", "ne", "<>", "gt", ">", "gte", ">=", "lt", "<", "lte", "<=").get(operator);
                    if (comparator == null) throw invalid();
                    conditions.add(column + " " + comparator + " ?");
                    parameters.add(value(field, value));
                }
            }
        }
        if (!conditions.isEmpty()) sql.append(" where ").append(String.join(" and ", conditions));
        if (!group.isEmpty()) sql.append(" group by ").append(String.join(",", group));
        List<String> order = new ArrayList<>();
        for (JsonNode item : input.path("orderBy"))
        {
            String name = item.path("field").asText();
            if (!output.contains(name)) throw invalid();
            order.add(quote(name) + " " + item.path("direction").asText());
        }
        // 明细默认按所选列排序；分页结果是当前时刻视图，不承诺跨请求的静态快照。
        if (order.isEmpty()) order.add(quote(output.get(0)) + " ASC");
        sql.append(" order by ").append(String.join(",", order)).append(" limit ? offset ?");
        int limit = input.path("limit").asInt(50), offset = input.path("offset").asInt(0);
        parameters.add(limit + 1);
        parameters.add(offset);
        return new Compiled(sql.toString(), List.copyOf(parameters), limit, offset, List.copyOf(output));
    }

    private static Object value(Field field, String value)
    {
        if ("number".equals(field.type()))
        {
            try
            {
                BigDecimal number = new BigDecimal(value);
                if (number.precision() > 30 || Math.abs(number.scale()) > 10) throw invalid();
                return number;
            }
            catch (NumberFormatException error) { throw invalid(); }
        }
        if ("boolean".equals(field.type()))
        {
            if (!Set.of("0", "1", "true", "false").contains(value)) throw invalid();
            return "1".equals(value) || "true".equals(value);
        }
        return value;
    }

    private static String quote(String value)
    {
        if (!AiDataCatalog.identifier(value)) throw invalid();
        return "`" + value + "`";
    }

    private static Map<String, Object> array(Map<String, Object> item, int minimum, int maximum)
    {
        return Map.of("type", "array", "items", item, "minItems", minimum, "maxItems", maximum);
    }

    private static ServiceException invalid() { return new ServiceException("查询组合无效，须使用已授权字段和受支持的只读运算"); }
}
