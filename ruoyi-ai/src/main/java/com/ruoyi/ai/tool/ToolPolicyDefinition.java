package com.ruoyi.ai.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ToolPolicyDefinition(String riskLevel, String requiredPermission, String description,
        Map<String, Object> inputSchema)
{
    public String defaultDescription()
    {
        return description;
    }

    public static Map<String, Object> flexibleObjectSchema()
    {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        return Map.copyOf(schema);
    }

    public static Map<String, Object> objectSchema(Map<String, ?> properties, List<String> required)
    {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty())
        {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }
}
