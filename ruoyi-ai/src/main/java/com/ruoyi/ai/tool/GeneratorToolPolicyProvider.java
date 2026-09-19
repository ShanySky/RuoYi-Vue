package com.ruoyi.ai.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import static com.ruoyi.ai.tool.ToolPolicyDefinition.objectSchema;
import static com.ruoyi.ai.tool.ToolPolicyDefinition.flexibleObjectSchema;

@Component
public class GeneratorToolPolicyProvider implements ToolPolicyProvider
{
    private final Map<String, ToolPolicyDefinition> policies;

    public GeneratorToolPolicyProvider()
    {
        Map<String, ToolPolicyDefinition> values = new LinkedHashMap<>();
        values.put("page_tool_gen_preview", new ToolPolicyDefinition(
                        "READ", "tool:gen:preview", "预览指定生成表的代码",
                        objectSchema(Map.of("tableId", Map.of("type", "integer")), List.of("tableId"))));
        values.put("page_tool_gen_sync_db", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "tool:gen:edit", "将生成配置与数据库表结构强制同步",
                        objectSchema(Map.of("tableId", Map.of("type", "integer")), List.of("tableId"))));
        values.put("page_tool_gen_generate", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "tool:gen:code", "按当前生成配置生成代码；自定义路径模式可能写入服务器文件",
                        objectSchema(Map.of("tableId", Map.of("type", "integer")), List.of("tableId"))));
        values.put("page_tool_gen_edit_view", new ToolPolicyDefinition(
                        "READ", "tool:gen:query", "查看当前代码生成配置与字段配置",
                        objectSchema(Map.of(), List.of())));
        values.put("page_tool_gen_edit_set_info", new ToolPolicyDefinition(
                        "UI", "tool:gen:edit", "修改当前代码生成基础/生成信息但不保存",
                        flexibleObjectSchema()));
        values.put("page_tool_gen_edit_set_column", new ToolPolicyDefinition(
                        "UI", "tool:gen:edit", "修改指定生成字段配置但不保存",
                        flexibleObjectSchema()));
        values.put("page_tool_gen_edit_reorder_columns", new ToolPolicyDefinition(
                        "UI", "tool:gen:edit", "调整当前生成字段顺序但不保存",
                        objectSchema(Map.of("columnIds", Map.of("type", "array", "items", Map.of("type", "integer"))), List.of("columnIds"))));
        values.put("page_tool_gen_edit_select_tab", new ToolPolicyDefinition(
                        "UI", "tool:gen:query", "切换代码生成配置页签",
                        objectSchema(Map.of("tab", Map.of("type", "string", "enum", List.of("basic", "columnInfo", "genInfo"))), List.of("tab"))));
        values.put("page_tool_gen_edit_submit", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "tool:gen:edit", "提交代码生成配置；会改变后续生成代码内容和路径",
                        objectSchema(Map.of(), List.of())));

        policies = Map.copyOf(values);
    }

    @Override
    public ToolPolicyDefinition resolve(String toolName)
    {
        return policies.get(toolName);
    }
}
