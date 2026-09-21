package com.ruoyi.ai.data;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.ai.mapper.AiServerCallMapper;
import com.ruoyi.ai.mapper.AiServerCallMapper.Call;
import com.ruoyi.ai.server.ApiContractSchema;
import com.ruoyi.ai.tool.AiFrontendToolPolicy.ApprovedTool;
import com.ruoyi.common.utils.SecurityUtils;

@Service
public class AiDataTools
{
    public static final String SEARCH = "server_data_search", DESCRIBE = "server_data_describe";
    private final AiDataAccess access;
    private final AiDataCatalog catalog;
    private final AiDataQuery query;
    private final AiDataExecutor executor;
    private final AiServerCallMapper calls;

    public AiDataTools(AiDataAccess access, AiDataCatalog catalog, AiDataQuery query, AiDataExecutor executor, AiServerCallMapper calls)
    {
        this.access = access;
        this.catalog = catalog;
        this.query = query;
        this.executor = executor;
        this.calls = calls;
    }

    public boolean supports(String name)
    {
        return SEARCH.equals(name) || DESCRIBE.equals(name) || (name != null && name.matches("data_[a-z_]{1,50}"));
    }

    public List<ApprovedTool> discovery()
    {
        var search = ApiContractSchema.object();
        ApiContractSchema.add(search, "query", Map.of("type", "string", "maxLength", 100), false);
        ApiContractSchema.add(search, "table", Map.of("type", "string", "maxLength", 64), false);
        ApiContractSchema.add(search, "page", Map.of("type", "integer", "minimum", 1, "maximum", 1000), false);
        var describe = ApiContractSchema.object();
        ApiContractSchema.add(describe, "id", Map.of("type", "string", "maxLength", 64), true);
        return List.of(new ApprovedTool(SEARCH, "搜索本人可用的数据查询分析视图，只返回摘要；用用户、部门、岗位等短关键词，或省略浏览。", search, "READ", null),
                new ApprovedTool(DESCRIBE, "加载数据视图的已授权字段和只读操作，下一轮获得该视图具体查询工具；结果由 server_api_result 分页读取。", describe, "READ", null));
    }

    public ApprovedTool definition(String id, String fingerprint)
    {
        var allowed = access.require(id);
        if (!allowed.view().fingerprint().equals(fingerprint)) throw new com.ruoyi.common.exception.ServiceException("数据结构已变化");
        return new ApprovedTool(id, allowed.view().binding().title()
                + "：仅查询本人原业务行范围。QUERY 必填 columns；AGGREGATE 必填 metrics，可选 groupBy。统计结果依次名为 metric_1 等，可排序。"
                + "COUNT 可省略 field 以统计行数；其他统计只接受数值字段。筛选值均用字符串。查询最多100行和128KiB。"
                + "返回句柄后用 server_api_result、jsonPointer=/rows 读取；读取工具的 limit 最多50，与查询上限不同。",
                query.schema(allowed), "READ", allowed.view().api().permission());
    }

    public String authorization(String id) { return access.require(id).authorization(); }

    public Object discovery(Call call, JsonNode args)
    {
        if (SEARCH.equals(call.toolName()))
        {
            String text = args.path("query").asText("").toLowerCase(java.util.Locale.ROOT), table = args.path("table").asText("");
            var found = access.visible().stream().filter(value -> (text.isEmpty()
                    || (value.view().binding().title() + value.view().binding().tables()).toLowerCase(java.util.Locale.ROOT).contains(text))
                    && (table.isEmpty() || value.view().binding().tables().contains(table))).toList();
            int start = Math.min(found.size(), (args.path("page").asInt(1) - 1) * 20);
            var page = found.subList(start, Math.min(start + 20, found.size()));
            page.forEach(value -> calls.addSource(call.conversationId(), value.view().id(), value.authorization()));
            return Map.of("total", found.size(), "items", page.stream().map(AiDataAccess::summary).toList(),
                    "notice", found.isEmpty() ? "当前没有匹配的可用数据能力；可改用短业务词或空查询浏览。目录为空不代表业务数据为零，无法查询时应明确说明。"
                            : "先加载视图详情，再查询真实行或统计值；目录数量不是业务记录数量。");
        }
        var allowed = access.require(args.path("id").asText());
        calls.addSource(call.conversationId(), allowed.view().id(), allowed.authorization());
        calls.load(call.runId(), allowed.view().id(), allowed.view().fingerprint());
        Map<String, Object> description = AiDataAccess.summary(allowed);
        description.put("fields", allowed.fields().stream().map(field -> Map.of("name", field.name(), "table", field.table(), "column", field.column(), "type", field.type())).toList());
        description.put("loadedTool", allowed.view().id());
        description.put("notice", "下一轮使用同名工具；只能提交结构化只读查询，业务写入继续使用原业务接口。");
        return description;
    }

    public JsonNode execute(Call call, JsonNode args)
    {
        var allowed = access.require(call.capabilityId());
        if (!call.authorizationHash().equals(allowed.authorization())) throw new com.ruoyi.common.exception.ServiceException("数据授权已变化");
        String source = catalog.sourceSql(allowed.view().binding(), SecurityUtils.getLoginUser().getUser(), allowed.view().api().permission());
        return executor.execute(call.userId(), call.runId(), query.compile(allowed, source, args));
    }
}
