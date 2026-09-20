package com.ruoyi.ai.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ruoyi.ai.domain.AiPendingToolCall;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.mapper.AiPendingToolCallMapper;
import com.ruoyi.ai.mapper.AiRunMapper;
import com.ruoyi.ai.mapper.AiServerCallMapper;
import com.ruoyi.ai.mapper.AiServerCallMapper.Call;
import com.ruoyi.ai.server.AiApiCatalog.Capability;
import com.ruoyi.ai.tool.AiFrontendToolPolicy.ApprovedTool;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

/** 接口阶段的服务端工具接入，运行与消息状态仍由既有协调器负责。 */
@Service
public class AiServerToolService
{
    public static final String SEARCH = "server_api_search";
    public static final String DESCRIBE = "server_api_describe";
    public static final String RESULT = "server_api_result";
    private static final int MAX_DISCLOSURE_BYTES = 8192;
    private final AiApiAccess access;
    private final AiNativeApiInvoker invoker;
    private final AiServerCallMapper calls;
    private final AiPendingToolCallMapper pending;
    private final AiRunMapper runs;
    private final ObjectMapper json;
    private final ApiContractSchema schemas;
    private final TransactionTemplate transactions;

    public AiServerToolService(AiApiAccess access, AiNativeApiInvoker invoker, AiServerCallMapper calls,
            AiPendingToolCallMapper pending, AiRunMapper runs, ObjectMapper json, ApiContractSchema schemas,
            org.springframework.transaction.PlatformTransactionManager transactionManager)
    {
        this.access = access;
        this.invoker = invoker;
        this.calls = calls;
        this.pending = pending;
        this.runs = runs;
        this.json = json;
        this.schemas = schemas;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public boolean supports(String name)
    {
        return SEARCH.equals(name) || DESCRIBE.equals(name) || RESULT.equals(name)
                || (name != null && name.matches("api_[a-f0-9]{24}"));
    }

    public List<ApprovedTool> definitions(AiRun run)
    {
        List<ApprovedTool> definitions = new ArrayList<>();
        Map<String, Object> search = ApiContractSchema.object();
        ApiContractSchema.add(search, "query", Map.of("type", "string", "maxLength", 100,
                "description", "短关键词，例如岗位、用户；可省略以浏览目录"), false);
        ApiContractSchema.add(search, "module", Map.of("type", "string", "maxLength", 64,
                "description", "仅填写先前搜索结果返回的模块标识；未知时省略"), false);
        ApiContractSchema.add(search, "object", Map.of("type", "string", "maxLength", 64,
                "description", "仅填写先前搜索结果返回的业务对象标识；未知时省略"), false);
        ApiContractSchema.add(search, "page", Map.of("type", "integer", "minimum", 1, "maximum", 1000), false);
        definitions.add(tool(SEARCH, "搜索本人已获授权的业务接口，按模块和业务对象筛选；结果只提供摘要，需要先加载详情。", search));
        Map<String, Object> describe = ApiContractSchema.object();
        ApiContractSchema.add(describe, "id", Map.of("type", "string"), true);
        definitions.add(tool(DESCRIBE, "按搜索结果中的 id 加载接口。下一轮获得该具体工具的参数、约束和风险；最多同时加载 8 个。", describe));
        Map<String, Object> result = ApiContractSchema.object();
        ApiContractSchema.add(result, "resultId", Map.of("type", "string"), true);
        ApiContractSchema.add(result, "jsonPointer", Map.of("type", "string", "maxLength", 200), false);
        ApiContractSchema.add(result, "offset", Map.of("type", "integer", "minimum", 0, "maximum", 100000), false);
        ApiContractSchema.add(result, "limit", Map.of("type", "integer", "minimum", 1, "maximum", 50), false);
        definitions.add(tool(RESULT, "读取本任务结果句柄。支持 JSON 路径（例如 /rows）、数组偏移和条数；每次最多 8 KiB。", result));
        for (var loaded : calls.loaded(run.getRunId()))
        {
            try
            {
                Capability capability = access.require(loaded.capabilityId());
                if (capability.fingerprint().equals(loaded.contractHash()))
                    definitions.add(new ApprovedTool(capability.id(), capability.title() + "；" + capability.method()
                            + " " + capability.path() + ("WRITE".equals(capability.riskLevel())
                            ? "。调用此工具先创建用户确认卡，确认后才由服务端执行写入；不要用文字确认代替工具调用。"
                            : "。执行结果按句柄读取。"), capability.inputSchema(),
                            capability.riskLevel(), capability.permission()));
            }
            catch (ServiceException ignored)
            {
                // 撤权后不再披露已加载定义；执行时仍有独立鉴权。
            }
        }
        return definitions;
    }

    public void prepare(AiPendingToolCall tool)
    {
        AiRun run = runs.selectById(tool.getRunId());
        if (run == null || !run.getUserId().equals(SecurityUtils.getUserId())) throw denied();
        ApprovedTool definition = definitions(run).stream().filter(value -> value.name().equals(tool.getToolName()))
                .findFirst().orElseThrow(this::denied);
        JsonNode args = arguments(tool);
        schemas.validate(definition.inputSchema(), args);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("callId", tool.getCallId());
        values.put("conversationId", tool.getConversationId());
        values.put("runId", tool.getRunId());
        values.put("userId", tool.getUserId());
        values.put("toolName", tool.getToolName());
        values.put("riskLevel", definition.riskLevel());
        if (tool.getToolName().startsWith("api_"))
        {
            Capability capability = access.require(tool.getToolName());
            values.put("capabilityId", capability.id());
            values.put("authorizationHash", access.authorization(capability));
            if ("WRITE".equals(definition.riskLevel()))
            {
                if (calls.unknownWrites(tool.getConversationId()) > 0)
                    throw new ServiceException("存在结果未知的业务写入，须先核对真实业务结果");
                values.put("dedupeKey", AiApiCatalog.hash(tool.getRunId() + ":" + capability.id() + ":" + canonical(args)));
            }
        }
        try { calls.insert(values); }
        catch (org.springframework.dao.DuplicateKeyException error)
        {
            throw new ServiceException("本轮相同业务写入已提交，不能重复执行；请先查询核对结果");
        }
    }

    public void execute(Long conversationId, String callId, boolean approved)
    {
        Call call = owned(callId);
        if (!call.conversationId().equals(conversationId)) throw denied();
        AiPendingToolCall tool = pending.selectByCall(conversationId, callId);
        if (tool == null) throw denied();
        if (!"PENDING".equals(call.status())) return;
        if (calls.claim(callId) != 1) throw new ServiceException("调用已处理或运行已停止");
        if (!approved)
        {
            finish(call, "CANCELLED", null, null, Map.of("success", false, "error", "用户取消了操作"), "USER_CANCELLED");
            return;
        }
        boolean nativeStarted = false;
        try
        {
            JsonNode args = arguments(tool);
            Object output;
            if (SEARCH.equals(call.toolName())) output = search(call, args);
            else if (DESCRIBE.equals(call.toolName())) output = describe(call, args);
            else if (RESULT.equals(call.toolName())) output = readResult(call, args);
            else
            {
                Capability capability = access.require(call.capabilityId());
                if (!call.authorizationHash().equals(access.authorization(capability)))
                    throw new ServiceException("业务授权或接口策略已变化");
                schemas.validate(capability.inputSchema(), args);
                // 在任何业务内容进入会话前记录来源，使后续历史与检查点也受同一授权约束。
                calls.addSource(call.conversationId(), capability.id(), call.authorizationHash());
                JsonNode result = invoker.invoke(call.callId(), capability, args);
                nativeStarted = true;
                if (!call.authorizationHash().equals(access.authorization(capability)))
                    throw new ServiceException("执行期间业务授权已变化，结果不再披露");
                boolean success = !result.has("code") || result.path("code").asInt() == 200;
                String resultId = UUID.randomUUID().toString().replace("-", "");
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("resultId", resultId);
                summary.put("code", result.path("code").asInt(200));
                summary.put("rootKeys", keys(result));
                summary.put("bytes", bytes(result));
                if (result.has("total")) summary.put("total", result.get("total"));
                finish(call, success ? "SUCCEEDED" : "FAILED", resultId, json.writeValueAsString(result),
                        Map.of("success", success, "result", summary), success ? null : "BUSINESS_REJECTED");
                return;
            }
            if (bytes(output) > MAX_DISCLOSURE_BYTES) throw new ServiceException("披露结果过大，请缩小搜索或读取范围");
            finish(call, "SUCCEEDED", null, null, Map.of("success", true, "result", output), null);
        }
        catch (Exception error)
        {
            boolean unknown = "WRITE".equals(call.riskLevel())
                    && (nativeStarted || error instanceof AiNativeApiInvoker.UncertainExecutionException);
            String message = unknown ? "业务写入结果未知，禁止重试；请通过查询核对真实结果"
                    : error instanceof ServiceException ? error.getMessage() : "服务端工具执行失败，请检查运行记录";
            finish(call, unknown ? "UNKNOWN" : "FAILED", null, null,
                    Map.of("success", false, "error", message), unknown ? "RESULT_UNKNOWN" : "EXECUTION_REJECTED");
        }
    }

    public JsonNode trustedResult(Long conversationId, String callId)
    {
        Call call = owned(callId);
        if (!call.conversationId().equals(conversationId) || call.toolResultJson() == null
                || List.of("PENDING", "EXECUTING").contains(call.status()))
            throw new ServiceException("服务端尚未完成该调用，不能提交浏览器结果");
        if (call.capabilityId() != null && "SUCCEEDED".equals(call.status()))
        {
            Capability capability = access.require(call.capabilityId());
            if (!call.authorizationHash().equals(access.authorization(capability))) throw denied();
        }
        try { return json.readTree(call.toolResultJson()); }
        catch (Exception error) { throw new ServiceException("服务端工具结果已失效"); }
    }

    public Map<String, String> outcome(String callId)
    {
        return Map.of("status", owned(callId).status());
    }

    private Object search(Call call, JsonNode args)
    {
        String query = args.path("query").asText("").toLowerCase(java.util.Locale.ROOT);
        String module = args.path("module").asText("");
        String object = args.path("object").asText("");
        List<Capability> found = access.visible().stream().filter(capability ->
                (module.isEmpty() || capability.module().equals(module))
                && (object.isEmpty() || capability.object().equals(object))
                && matches(capability, query)).toList();
        int start = Math.min(found.size(), Math.max(0, args.path("page").asInt(1) - 1) * 20);
        List<Capability> page = found.subList(start, Math.min(start + 20, found.size()));
        for (Capability capability : page)
            calls.addSource(call.conversationId(), capability.id(), access.authorization(capability));
        return Map.of("total", found.size(), "items", page.stream().map(AiApiAccess::summary).toList(),
                "notice", found.isEmpty() ? "未找到匹配项；请省略未知模块和对象标识，改用短关键词或空查询浏览本人目录。"
                        : "使用返回的模块和对象标识筛选；每轮加载或执行一个工具。");
    }

    private boolean matches(Capability capability, String query)
    {
        String text = (capability.title() + " " + capability.permission() + " " + capability.path())
                .toLowerCase(java.util.Locale.ROOT);
        if (query.isEmpty() || text.contains(query)) return true;
        // 中文整句中连续两字可匹配真实业务名称，无需维护第二份业务词表。
        for (int index = 0; index + 1 < query.length(); index++)
        {
            if (Character.UnicodeScript.of(query.charAt(index)) == Character.UnicodeScript.HAN
                    && Character.UnicodeScript.of(query.charAt(index + 1)) == Character.UnicodeScript.HAN
                    && capability.title().contains(query.substring(index, index + 2))) return true;
        }
        return false;
    }

    private Object describe(Call call, JsonNode args)
    {
        Capability capability = access.require(args.path("id").asText());
        calls.addSource(call.conversationId(), capability.id(), access.authorization(capability));
        calls.load(call.runId(), capability.id(), capability.fingerprint());
        Map<String, Object> result = AiApiAccess.summary(capability);
        result.put("loadedTool", capability.id());
        result.put("notice", "下一轮使用同名工具及其真实参数模式；写入需要用户确认。");
        return result;
    }

    private Object readResult(Call reader, JsonNode args)
    {
        Call source = calls.result(args.path("resultId").asText());
        if (source == null || !source.userId().equals(reader.userId()) || !source.runId().equals(reader.runId())) throw denied();
        Capability capability = access.require(source.capabilityId());
        if (!source.authorizationHash().equals(access.authorization(capability))) throw denied();
        try
        {
            JsonNode root = json.readTree(source.resultJson());
            String pointer = args.path("jsonPointer").asText("");
            JsonNode selected = pointer.isEmpty() ? root : root.at(pointer);
            if (selected.isMissingNode()) throw new ServiceException("结果中不存在指定路径");
            if (selected.isArray())
            {
                int offset = args.path("offset").asInt(0);
                int limit = args.path("limit").asInt(10);
                List<JsonNode> page = new ArrayList<>();
                for (int index = offset; index < selected.size() && page.size() < limit; index++) page.add(selected.get(index));
                return Map.of("items", page, "total", selected.size(), "offset", offset, "nextOffset", offset + page.size());
            }
            return selected;
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("结果路径或内容不可读取"); }
    }

    private void finish(Call call, String status, String resultId, String result, Object toolResult, String error)
    {
        String toolJson;
        try { toolJson = json.writeValueAsString(toolResult); }
        catch (Exception failure) { throw new ServiceException("工具结果无法保存"); }
        transactions.executeWithoutResult(transaction -> {
            if (result != null)
            {
                calls.lockResultQuota();
                if (calls.resultCount(call.userId()) >= 64 || calls.resultCount(null) >= 1024)
                    throw new ServiceException("结果空间已达上限，请等待过期清理");
            }
            if (calls.finish(call.callId(), status, resultId, result, toolJson, error) != 1)
                throw new ServiceException("调用结果状态已变化，不能覆盖原执行事实");
        });
    }

    private Call owned(String callId)
    {
        Call call = calls.get(callId);
        if (call == null || !SecurityUtils.getUserId().equals(call.userId())) throw denied();
        return call;
    }

    private JsonNode arguments(AiPendingToolCall call)
    {
        try { return json.readTree(call.getArgumentsJson()); }
        catch (Exception error) { throw new ServiceException("工具参数不是有效 JSON"); }
    }

    private String canonical(JsonNode value)
    {
        try { return json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(json.convertValue(value, Map.class)); }
        catch (Exception error) { throw new ServiceException("工具参数无法归一化"); }
    }

    private int bytes(Object value)
    {
        try { return json.writeValueAsBytes(value).length; }
        catch (Exception error) { throw new ServiceException("工具结果无法编码"); }
    }

    private List<String> keys(JsonNode value)
    {
        List<String> keys = new ArrayList<>();
        value.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private ApprovedTool tool(String name, String description, Map<String, Object> schema)
    {
        return new ApprovedTool(name, description, schema, "READ", null);
    }

    private ServiceException denied() { return new ServiceException("服务端能力或结果不存在、已失效或无权访问"); }

    @Scheduled(fixedDelay = 60000)
    public void cleanup()
    {
        calls.expireResults();
        calls.recoverInterrupted();
        calls.cleanLoaded();
        calls.cleanOrphanCalls();
        calls.cleanOrphanSources();
    }
}
