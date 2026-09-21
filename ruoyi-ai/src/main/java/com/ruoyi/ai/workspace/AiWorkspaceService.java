package com.ruoyi.ai.workspace;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.mapper.AiConversationMapper;
import com.ruoyi.ai.mapper.AiRunMapper;
import com.ruoyi.ai.mapper.AiServerCallMapper;
import com.ruoyi.ai.mapper.AiWorkspaceMapper;
import com.ruoyi.ai.mapper.AiWorkspaceMapper.Artifact;
import com.ruoyi.ai.server.AiBusinessAccess;
import com.ruoyi.ai.server.AiBusinessHistoryGuard;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

@Service
public class AiWorkspaceService
{
    private record Active(Long userId, Long conversationId, Authentication authentication) { }
    private final Map<Long, Active> active = new ConcurrentHashMap<>();
    private final AiWorkspaceClient client;
    private final AiWorkspaceAccess access;
    private final AiBusinessAccess business;
    private final AiBusinessHistoryGuard history;
    private final AiWorkspaceMapper artifacts;
    private final AiServerCallMapper calls;
    private final AiRunMapper runs;
    private final AiConversationMapper conversations;

    public AiWorkspaceService(AiWorkspaceClient client, AiWorkspaceAccess access, AiBusinessAccess business,
            AiBusinessHistoryGuard history, AiWorkspaceMapper artifacts, AiServerCallMapper calls,
            AiRunMapper runs, AiConversationMapper conversations)
    {
        this.client = client; this.access = access; this.business = business; this.history = history;
        this.artifacts = artifacts; this.calls = calls; this.runs = runs; this.conversations = conversations;
    }

    public JsonNode execute(AiServerCallMapper.Call call, JsonNode args)
    {
        access.authorization();
        history.requireReadable(call.conversationId());
        AiRun run = runs.selectById(call.runId());
        if (run == null || !call.userId().equals(run.getUserId()) || !"WAITING_TOOL".equals(run.getStatus())) throw denied();
        // 原运行时间是无时区 DATETIME，使用数据库自身的时间差，避免跨环境时区偏移。
        Long age = runs.ageSeconds(call.runId());
        if (age == null || age < 0 || age >= 1800) throw new ServiceException("任务已超过工作空间的绝对执行时限");
        synchronized (active)
        {
            if (!active.containsKey(run.getRunId()) && (active.size() >= 4
                    || active.values().stream().filter(value -> value.userId().equals(call.userId())).count() >= 2))
                throw new ServiceException("本人或全局工作空间并发已达上限");
            active.put(run.getRunId(), new Active(call.userId(), call.conversationId(), SecurityContextHolder.getContext().getAuthentication()));
        }
        Map<String, Object> request = new HashMap<>(identity(call.userId(), call.runId()));
        request.put("runCreatedAt", System.currentTimeMillis() / 1000.0 - age);
        if ("workspace_execute".equals(call.toolName()))
        {
            request.put("command", args.path("command").asText());
            request.put("timeoutSeconds", args.path("timeoutSeconds").asInt(30));
            return client.call("execute", request);
        }
        request.put("path", args.path("path").asText());
        if ("workspace_import_result".equals(call.toolName()))
        {
            var source = calls.result(args.path("resultId").asText());
            if (source == null || !source.runId().equals(call.runId()) || !source.userId().equals(call.userId())
                    || !source.authorizationHash().equals(business.authorization(source.capabilityId()))) throw denied();
            request.put("content", source.resultJson());
            return client.call("import", request);
        }
        if (!"workspace_publish".equals(call.toolName())) throw denied();
        request.put("name", args.path("name").asText());
        JsonNode receipt = client.call("publish", request);
        String id = receipt.path("id").asText();
        try
        {
            if (!id.matches("[a-f0-9]{32}") || receipt.path("bytes").asLong() > 8 * 1024 * 1024) throw denied();
            access.authorization();
            history.requireReadable(call.conversationId());
            if (!"WAITING_TOOL".equals(runs.selectById(call.runId()).getStatus())) throw denied();
            artifacts.insert(Map.of("id", id, "userId", call.userId(), "conversationId", call.conversationId(),
                    "runId", call.runId(), "name", receipt.path("name").asText(), "bytes", receipt.path("bytes").asLong(),
                    "sha256", receipt.path("sha256").asText(), "expiresAt", (long) (receipt.path("expiresAt").asDouble() * 1000)));
            return receipt;
        }
        catch (Exception error)
        {
            if (id.matches("[a-f0-9]{32}")) client.call("delete", Map.of("userId", call.userId(), "runId", call.runId(), "id", id));
            throw error;
        }
    }

    public List<Artifact> list(Long conversationId)
    {
        requireConversation(conversationId);
        return artifacts.list(conversationId);
    }

    public Artifact requireArtifact(String id)
    {
        Artifact artifact = artifacts.get(id);
        if (artifact == null || !SecurityUtils.getUserId().equals(artifact.userId())
                || artifact.expiresAt() <= System.currentTimeMillis()) throw denied();
        requireConversation(artifact.conversationId());
        return artifact;
    }

    public void requireConversation(Long conversationId)
    {
        access.authorization();
        var conversation = conversations.selectById(conversationId);
        if (conversation == null || !SecurityUtils.getUserId().equals(conversation.getUserId()) || "DELETED".equals(conversation.getStatus())) throw denied();
        history.requireReadable(conversationId);
    }

    public byte[] read(Artifact artifact)
    {
        byte[] data = client.read(Map.of("userId", artifact.userId(), "runId", artifact.runId(), "id", artifact.id()));
        try
        {
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
            if (data.length != artifact.bytes() || !hash.equals(artifact.sha256())) throw denied();
        }
        catch (java.security.NoSuchAlgorithmException error) { throw denied(); }
        requireConversation(artifact.conversationId());
        return data;
    }

    @Scheduled(fixedDelay = 2000, scheduler = "aiRuntimeScheduler")
    public void renew()
    {
        var renewable = new java.util.ArrayList<Map<String, Object>>();
        for (var entry : active.entrySet())
        {
            var previous = SecurityContextHolder.getContext();
            try
            {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(entry.getValue().authentication());
                SecurityContextHolder.setContext(context);
                var run = runs.selectById(entry.getKey());
                if (run == null || !com.ruoyi.ai.domain.AiRunStatus.from(run.getStatus()).isActive()
                        || "CANCEL_REQUESTED".equals(run.getStatus())) throw denied();
                requireConversation(entry.getValue().conversationId());
                renewable.add(identity(entry.getValue().userId(), entry.getKey()));
            }
            catch (Exception denied)
            {
                try
                {
                    if (client.call("stop", identity(entry.getValue().userId(), entry.getKey())).path("stopped").asBoolean())
                        active.remove(entry.getKey(), entry.getValue());
                }
                catch (Exception unavailable) { /* 服务端租约到期继续回收，不因网络故障延长授权。 */ }
            }
            finally { SecurityContextHolder.setContext(previous); }
        }
        if (!renewable.isEmpty())
        {
            try { client.call("lease", Map.of("tasks", renewable)); }
            catch (Exception ignored) { /* 租约不可用时，隔离服务自行终止执行。 */ }
        }
    }

    @Scheduled(fixedDelay = 60000, scheduler = "aiRuntimeScheduler")
    public void cleanup()
    {
        for (Artifact artifact : artifacts.expired())
        {
            try
            {
                client.call("delete", Map.of("userId", artifact.userId(), "runId", artifact.runId(), "id", artifact.id()));
                artifacts.delete(artifact.id());
            }
            catch (Exception ignored) { /* 读取中或回收失败继续保留元数据和配额，下一轮重试。 */ }
        }
    }

    @PreDestroy public void shutdown()
    {
        active.forEach((runId, value) -> {
            try { client.call("stop", identity(value.userId(), runId)); }
            catch (Exception ignored) { /* 异常退出依赖独立服务的短租约回收。 */ }
        });
    }

    private static Map<String, Object> identity(Long userId, Long runId) { return Map.of("userId", userId, "runId", runId); }
    private static ServiceException denied() { return new ServiceException("任务或成果不存在、已停止、已到期或当前无权访问"); }
}
