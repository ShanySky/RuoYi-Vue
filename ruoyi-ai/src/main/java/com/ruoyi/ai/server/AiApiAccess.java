package com.ruoyi.ai.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.ruoyi.ai.mapper.AiApiPolicyMapper;
import com.ruoyi.ai.mapper.AiApiPolicyMapper.Policy;
import com.ruoyi.ai.server.AiApiCatalog.Capability;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

@Service
public class AiApiAccess
{
    private final AiApiCatalog catalog;
    private final AiApiPolicyMapper policies;
    private final AiFreshIdentity identity;

    public AiApiAccess(AiApiCatalog catalog, AiApiPolicyMapper policies, AiFreshIdentity identity)
    {
        this.catalog = catalog;
        this.policies = policies;
        this.identity = identity;
    }

    public List<Map<String, Object>> governance()
    {
        Map<String, Policy> saved = new LinkedHashMap<>();
        policies.all().forEach(policy -> saved.put(policy.capabilityId(), policy));
        var labels = policies.labels();
        return catalog.all().stream().map(value -> display(value, labels)).map(capability -> {
            Map<String, Object> row = summary(capability);
            Policy policy = saved.get(capability.id());
            row.put("enabled", open(capability, policy));
            row.put("supported", capability.supported());
            row.put("unsupportedReason", capability.unsupportedReason());
            row.put("contractChanged", policy != null && !capability.fingerprint().equals(policy.contractHash()));
            row.put("fingerprint", capability.fingerprint());
            return row;
        }).toList();
    }

    public void configure(String id, String fingerprint, boolean enabled)
    {
        Capability capability = catalog.require(id);
        if (enabled && !capability.supported()) throw new ServiceException("该接口尚不支持自动开放");
        if (!capability.fingerprint().equals(fingerprint)) throw new ServiceException("接口契约已变化，请刷新后核对");
        policies.save(id, capability.fingerprint(), enabled, SecurityUtils.getUsername());
    }

    public List<Capability> visible()
    {
        LoginUser user = identity.refresh();
        Map<String, Policy> saved = new LinkedHashMap<>();
        policies.all().forEach(policy -> saved.put(policy.capabilityId(), policy));
        var labels = policies.labels();
        return catalog.all().stream().filter(capability -> capability.granted(user)
                && open(capability, saved.get(capability.id()))).map(value -> display(value, labels)).toList();
    }

    public Capability require(String id)
    {
        LoginUser user = identity.refresh();
        Capability capability = catalog.require(id);
        if (!capability.granted(user) || !open(capability, policies.get(id)))
            throw new ServiceException("接口未开放或当前用户无权使用");
        return display(capability, policies.labels());
    }

    public String authorization(Capability capability)
    {
        Capability latest = require(capability.id());
        Policy policy = policies.get(capability.id());
        if (!latest.fingerprint().equals(capability.fingerprint()) || !open(latest, policy))
            throw new ServiceException("接口契约或开放策略已变化");
        return AiApiCatalog.hash(identity.fingerprint(SecurityUtils.getLoginUser()) + ":"
                + capability.fingerprint() + ":" + policy.revision());
    }

    public static Map<String, Object> summary(Capability capability)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", capability.id());
        row.put("module", capability.module());
        row.put("object", capability.object());
        row.put("action", capability.action());
        row.put("title", capability.title());
        row.put("method", capability.method());
        row.put("path", capability.path());
        row.put("permission", capability.permission());
        row.put("riskLevel", capability.riskLevel());
        return row;
    }

    private boolean open(Capability capability, Policy policy)
    {
        return capability.supported() && policy != null && policy.enabled()
                && capability.fingerprint().equals(policy.contractHash());
    }

    private Capability display(Capability capability, List<AiApiPolicyMapper.Label> labels)
    {
        String title = labels.stream().filter(label -> java.util.Arrays.asList(capability.permission().split(","))
                .contains(label.permission())).map(AiApiPolicyMapper.Label::title).filter(java.util.Objects::nonNull)
                .findFirst().orElse(capability.title());
        return new Capability(capability.id(), capability.module(), capability.object(), capability.action(), title,
                capability.method(), capability.path(), capability.permission(), capability.riskLevel(),
                capability.inputSchema(), capability.fingerprint(), capability.unsupportedReason(), capability.pageable());
    }
}
