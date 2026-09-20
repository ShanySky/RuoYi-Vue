package com.ruoyi.ai.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.data.AiDataCatalog.Field;
import com.ruoyi.ai.data.AiDataCatalog.Snapshot;
import com.ruoyi.ai.data.AiDataCatalog.View;
import com.ruoyi.ai.mapper.AiDataPolicyMapper;
import com.ruoyi.ai.mapper.AiDataPolicyMapper.Policy;
import com.ruoyi.ai.server.AiApiCatalog;
import com.ruoyi.ai.server.AiFreshIdentity;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

@Service
public class AiDataAccess
{
    public record Allowed(View view, String database, List<Field> fields, Set<String> operations, String authorization) { }
    private final AiDataCatalog catalog;
    private final AiDataPolicyMapper policies;
    private final AiFreshIdentity identity;
    private final ObjectMapper json;

    public AiDataAccess(AiDataCatalog catalog, AiDataPolicyMapper policies, AiFreshIdentity identity, ObjectMapper json)
    {
        this.catalog = catalog;
        this.policies = policies;
        this.identity = identity;
        this.json = json;
    }

    public List<Allowed> visible()
    {
        LoginUser user = identity.refresh();
        Snapshot snapshot = catalog.snapshot();
        List<Allowed> result = new ArrayList<>();
        for (View view : snapshot.views())
        {
            try { result.add(allow(snapshot, view, user)); }
            catch (ServiceException ignored) { /* 未授权的视图不进入模型目录。 */ }
        }
        return result;
    }

    public Allowed require(String id)
    {
        LoginUser user = identity.refresh();
        Snapshot snapshot = catalog.snapshot();
        View view = snapshot.views().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(AiDataAccess::denied);
        return allow(snapshot, view, user);
    }

    private Allowed allow(Snapshot snapshot, View view, LoginUser user)
    {
        if (!view.api().granted(user)) throw denied();
        Policy database = policies.get("database");
        if (!open(database, snapshot.fingerprint())) throw denied();
        Map<String, Set<String>> fields = new LinkedHashMap<>();
        Set<String> operations = new TreeSet<>(Set.of("QUERY", "AGGREGATE"));
        StringBuilder revisions = new StringBuilder().append(database.revision());
        for (String name : new TreeSet<>(view.binding().tables()))
        {
            var table = snapshot.tables().stream().filter(value -> value.name().equals(name)).findFirst().orElseThrow(AiDataAccess::denied);
            Policy policy = policies.get(name);
            if (!table.supported() || !open(policy, table.fingerprint())) throw denied();
            fields.put(name, values(policy.fieldsJson()));
            operations.retainAll(values(policy.operationsJson()));
            revisions.append(':').append(name).append(':').append(policy.revision());
        }
        List<Field> exposed = view.fields().stream().filter(field -> fields.get(field.table()).contains(field.column())).toList();
        if (exposed.isEmpty() || operations.isEmpty()) throw denied();
        // 未知范围值在生成原查询前关闭，而不沿用空条件的宽松结果。
        catalog.sourceSql(view.binding(), user.getUser(), view.api().permission());
        String hash = AiApiCatalog.hash(identity.fingerprint(user) + ":" + view.fingerprint() + ":" + revisions);
        return new Allowed(view, snapshot.database(), exposed, Set.copyOf(operations), hash);
    }

    public Map<String, Object> governance()
    {
        Snapshot snapshot = catalog.snapshot();
        Map<String, Object> database = display("database", snapshot.database(), snapshot.fingerprint(), true);
        List<Map<String, Object>> tables = snapshot.tables().stream().map(table -> {
            Map<String, Object> row = display(table.name(), table.comment().isBlank() ? table.name() : table.comment(), table.fingerprint(), table.supported());
            row.put("columns", table.columns());
            row.put("views", snapshot.views().stream().filter(view -> view.binding().tables().contains(table.name()))
                    .map(view -> Map.of("id", view.id(), "title", view.binding().title(), "permission", view.api().permission())).toList());
            row.put("reason", table.supported() ? "" : "尚未建立可靠的原业务授权映射，禁止开放");
            return row;
        }).toList();
        database.put("tables", tables);
        return database;
    }

    public void configure(String key, String fingerprint, boolean enabled, Set<String> fields, Set<String> operations)
    {
        Snapshot snapshot = catalog.snapshot();
        Set<String> selectedFields = fields == null ? Set.of() : fields;
        Set<String> selectedOperations = operations == null ? Set.of() : operations;
        if (!Set.of("QUERY", "AGGREGATE").containsAll(selectedOperations)) throw denied();
        if ("database".equals(key))
        {
            if (!snapshot.fingerprint().equals(fingerprint) || !selectedFields.isEmpty() || !selectedOperations.isEmpty()) throw denied();
        }
        else
        {
            var table = snapshot.tables().stream().filter(value -> value.name().equals(key)).findFirst().orElseThrow(AiDataAccess::denied);
            Set<String> supported = new TreeSet<>();
            table.columns().stream().filter(AiDataCatalog.Column::supported).forEach(column -> supported.add(column.name()));
            if (!table.fingerprint().equals(fingerprint) || !supported.containsAll(selectedFields)
                    || (enabled && (!table.supported() || selectedFields.isEmpty() || selectedOperations.isEmpty()))) throw denied();
        }
        try { policies.save(key, fingerprint, enabled, json.writeValueAsString(new TreeSet<>(selectedFields)),
                json.writeValueAsString(new TreeSet<>(selectedOperations)), SecurityUtils.getUsername()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw denied(); }
    }

    private Map<String, Object> display(String key, String title, String hash, boolean supported)
    {
        Policy policy = policies.get(key);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("title", title);
        row.put("fingerprint", hash);
        row.put("supported", supported);
        row.put("enabled", supported && open(policy, hash));
        row.put("fields", policy == null ? Set.of() : values(policy.fieldsJson()));
        row.put("operations", policy == null ? Set.of() : values(policy.operationsJson()));
        row.put("contractChanged", policy != null && !hash.equals(policy.contractHash()));
        return row;
    }

    private Set<String> values(String source)
    {
        try { return json.readValue(source, new TypeReference<Set<String>>() { }); }
        catch (Exception error) { throw denied(); }
    }

    private static boolean open(Policy policy, String fingerprint)
    {
        return policy != null && policy.enabled() && policy.contractHash().equals(fingerprint);
    }

    public static Map<String, Object> summary(Allowed allowed)
    {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", allowed.view().id());
        summary.put("title", allowed.view().binding().title());
        summary.put("database", allowed.database());
        summary.put("tables", new TreeSet<>(allowed.view().binding().tables()));
        summary.put("operations", new TreeSet<>(allowed.operations()));
        return summary;
    }

    private static ServiceException denied() { return new ServiceException("数据能力未开放、结构已变化或当前用户无权使用"); }
}
