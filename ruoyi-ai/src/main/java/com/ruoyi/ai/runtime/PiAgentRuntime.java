package com.ruoyi.ai.runtime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiRunStatus;
import com.ruoyi.ai.mapper.AiRunMapper;
import com.ruoyi.ai.mapper.AiPendingToolCallMapper;
import com.ruoyi.ai.service.AiConfigService;
import com.ruoyi.ai.service.AiCryptoService;
import com.ruoyi.ai.service.AiTokenBudget;
import com.ruoyi.common.exception.ServiceException;

/** Pi 持有真实代理循环；本层只管理进程、回调和若依权威上下文。 */
@Service
public class PiAgentRuntime implements AgentRuntime
{
    private static final int MAX_PACKET = 2 * 1024 * 1024;
    private final AiConfigService configuration;
    private final AiCryptoService crypto;
    private final AiRunMapper runs;
    private final AiPendingToolCallMapper pending;
    private final ObjectMapper json;
    private final String node;
    private final Path home;
    private final Path state;
    private final Map<String, Worker> workers = new ConcurrentHashMap<>();

    public PiAgentRuntime(AiConfigService configuration, AiCryptoService crypto, AiRunMapper runs, AiPendingToolCallMapper pending, ObjectMapper json,
            @Value("${ruoyi.ai.harness.node:node}") String node,
            @Value("${ruoyi.ai.harness.home:runtime/pi}") String home,
            @Value("${ruoyi.profile}/ai-harness") String state)
    {
        this.configuration = configuration;
        this.crypto = crypto;
        this.runs = runs;
        this.pending = pending;
        this.json = json;
        this.node = node;
        this.home = Path.of(home).toAbsolutePath().normalize();
        this.state = Path.of(state).toAbsolutePath().normalize();
    }

    @Override public AgentRuntimeResult call(AgentRuntimeRequest request) throws Exception
    {
        var model = configuration.requireEnabledSystemModel(request.modelId());
        var provider = configuration.requireProvider();
        if (!provider.getProviderId().equals(model.getProviderId())) throw unavailable();
        AiTokenBudget.requireFits(model, request);
        var run = request.runId() == null ? null : runs.selectById(request.runId());
        if (request.runId() != null && (run == null || !AiRunStatus.from(run.getStatus()).isRunnable())) throw unavailable();
        long userId = run == null ? -1 : run.getUserId();
        String key = request.standalone() ? "single_" + UUID.randomUUID() : "run_" + request.runId();
        String binding = model.getModelId() + ":" + provider.getBaseUrl() + ":" + provider.getTokenCipher();
        int seconds = Math.min(120, Math.max(5, provider.getTimeoutSeconds() == null ? 30 : provider.getTimeoutSeconds()));
        Worker worker;
        synchronized (workers)
        {
            worker = workers.get(key);
            if (worker == null)
            {
                // 失去框架进程后不得以旧工具回执重新发起模型，避免隐式重放。
                if (!request.standalone() && !request.messages().isEmpty()
                        && request.messages().get(request.messages().size() - 1).role() == AgentRuntimeMessage.Role.TOOL) throw unavailable();
                if (workers.size() >= 4 || workers.values().stream().filter(value -> value.userId == userId).count() >= 2)
                    throw new ServiceException("执行框架并发已达上限，请稍后重试");
                worker = new Worker(key, request.runId(), userId, binding);
                workers.put(key, worker);
            }
        }
        try
        {
            synchronized (worker)
            {
                if (!binding.equals(worker.binding) || !worker.process.isAlive()) throw unavailable();
                if (worker.bridgeId == null && worker.started) throw unavailable();
                Map<String, Object> packet = new HashMap<>();
                packet.put("request", request);
                if (!worker.started)
                {
                    packet.put("type", "start");
                    packet.put("runtime", Map.of("model", model.getModelCode(), "baseUrl", provider.getBaseUrl(),
                            "apiKey", crypto.decrypt(provider.getTokenCipher()), "contextWindow", AiTokenBudget.window(model),
                            "outputLimit", AiTokenBudget.outputLimit(model), "timeoutMs", seconds * 1000));
                    worker.started = true;
                }
                else
                {
                    var last = request.messages().get(request.messages().size() - 1);
                    if (last.role() != AgentRuntimeMessage.Role.TOOL || !worker.toolName.equals(last.toolName())) throw unavailable();
                    packet.put("type", "resume");
                    packet.put("bridgeId", worker.bridgeId);
                    worker.bridgeId = null;
                }
                worker.send(packet);
                JsonNode result = worker.output.poll(seconds + 15L, TimeUnit.SECONDS);
                if (result != null && "UNAPPROVED_TOOL".equals(result.path("code").asText()))
                    throw new ServiceException("模型请求了当前页面不可用的工具：" + result.path("toolName").asText());
                if (result == null || "error".equals(result.path("type").asText())) throw unavailable();
                AgentRuntimeUsage usage = json.treeToValue(result.path("usage"), AgentRuntimeUsage.class);
                if ("tool".equals(result.path("type").asText()))
                {
                    if (request.standalone()) throw unavailable();
                    worker.bridgeId = result.path("bridgeId").asText();
                    worker.toolName = result.path("name").asText();
                    return new AgentRuntimeResult(model.getModelId(), model.getModelCode(), result.path("text").asText(),
                            List.of(new AgentRuntimeResult.ToolCall(worker.bridgeId, worker.toolName, result.path("arguments").asText())), usage, "toolUse");
                }
                if (!"result".equals(result.path("type").asText())) throw unavailable();
                worker.close();
                return new AgentRuntimeResult(model.getModelId(), model.getModelCode(), result.path("text").asText(), List.of(), usage, "stop");
            }
        }
        catch (Exception error) { worker.close(); throw error; }
    }

    @Scheduled(fixedDelay = 1000, scheduler = "aiRuntimeScheduler")
    public void reap()
    {
        for (Worker worker : workers.values())
        {
            var run = worker.runId == null ? null : runs.selectById(worker.runId);
            boolean ended = worker.runId != null && (run == null || AiRunStatus.from(run.getStatus()).isTerminal()
                    || "CANCEL_REQUESTED".equals(run.getStatus()));
            boolean exceeded = System.nanoTime() - worker.created > TimeUnit.MINUTES.toNanos(30)
                    || worker.process.info().totalCpuDuration().orElse(Duration.ZERO).compareTo(Duration.ofSeconds(120)) > 0
                    || !worker.storageWithinBudget();
            if (ended || exceeded || !worker.process.isAlive())
            {
                if (!ended && run != null) runs.transition(run.getRunId(), run.getStatus(), "FAILED", "HARNESS_PROCESS_EXITED");
                if (run != null) pending.cancelByRun(run.getRunId(), "CANCELLED");
                worker.close();
            }
        }
    }

    @PreDestroy public void shutdown() { List.copyOf(workers.values()).forEach(Worker::close); }

    @Scheduled(fixedDelay = 60000, scheduler = "aiRuntimeScheduler")
    public void cleanupOrphans()
    {
        if (!Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.list(state))
        {
            for (Path folder : entries.filter(path -> path.getFileName().toString().startsWith("pi_")).toList())
            {
                if (workers.values().stream().anyMatch(worker -> worker.folder.equals(folder))) continue;
                if (System.currentTimeMillis() - Files.getLastModifiedTime(folder, LinkOption.NOFOLLOW_LINKS).toMillis() < 30000) continue;
                removeFolder(folder);
            }
        }
        catch (Exception error) { org.slf4j.LoggerFactory.getLogger(PiAgentRuntime.class).warn("框架异常残留目录暂未回收，将继续重试"); }
    }

    private void removeFolder(Path folder) throws java.io.IOException
    {
        if (!folder.getParent().equals(state) || !folder.getFileName().toString().startsWith("pi_")) throw new java.io.IOException("任务目录归属无效");
        // 默认不跟随链接；仅回收本进程命名空间内的目录或链接本身。
        try (var paths = Files.walk(folder))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private final class Worker
    {
        private final String key, binding;
        private final Long runId;
        private final long userId, created = System.nanoTime();
        private final Path folder;
        private final Process process;
        private final ArrayBlockingQueue<JsonNode> output = new ArrayBlockingQueue<>(4);
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean started;
        private String bridgeId, toolName;

        private Worker(String key, Long runId, long userId, String binding) throws Exception
        {
            this.key = key;
            this.runId = runId;
            this.userId = userId;
            this.binding = binding;
            Path program = home.resolve("worker.mjs");
            if (!Files.isRegularFile(program) || !Files.isDirectory(home.resolve("node_modules"))) throw unavailable();
            Files.createDirectories(state);
            folder = Files.createTempDirectory(state, "pi_");
            ProcessBuilder builder = new ProcessBuilder(node, "--max-old-space-size=192", program.toString());
            builder.directory(folder.toFile());
            Map<String, String> inherited = new HashMap<>(builder.environment());
            builder.environment().clear();
            for (String name : List.of("SystemRoot", "WINDIR", "SystemDrive"))
                if (inherited.containsKey(name)) builder.environment().put(name, inherited.get(name));
            builder.environment().put("PATH", Path.of(node).isAbsolute() ? Path.of(node).getParent().toString() : "/usr/bin:/bin");
            builder.environment().put("LANG", "C.UTF-8");
            try { process = builder.start(); }
            catch (Exception error) { Files.deleteIfExists(folder); throw unavailable(); }
            Thread reader = new Thread(() -> read(process.getInputStream(), true), "pi-output-" + key);
            Thread errors = new Thread(() -> read(process.getErrorStream(), false), "pi-errors-" + key);
            reader.setDaemon(true);
            errors.setDaemon(true);
            reader.start();
            errors.start();
        }

        private void send(Object packet) throws Exception
        {
            byte[] bytes = json.writeValueAsBytes(packet);
            if (bytes.length > MAX_PACKET) throw unavailable();
            process.getOutputStream().write(bytes);
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
        }

        private void read(InputStream stream, boolean protocol)
        {
            try (stream; ByteArrayOutputStream line = new ByteArrayOutputStream())
            {
                byte[] buffer = new byte[4096];
                int read;
                long discarded = 0;
                while ((read = stream.read(buffer)) >= 0)
                {
                    if (!protocol)
                    {
                        // 不保存可能带上游信息的标准错误；异常刷屏会关闭该进程。
                        if ((discarded += read) > 64 * 1024) throw new IllegalStateException();
                        continue;
                    }
                    for (int index = 0; index < read; index++)
                    {
                        if (buffer[index] == '\n')
                        {
                            JsonNode packet = json.readTree(line.toString(StandardCharsets.UTF_8));
                            line.reset();
                            if (!output.offer(packet)) throw new IllegalStateException();
                        }
                        else
                        {
                            line.write(buffer[index]);
                            if (line.size() > MAX_PACKET) throw new IllegalStateException();
                        }
                    }
                }
            }
            catch (Exception ignored) { process.destroyForcibly(); /* 不传播框架原始日志或凭据。 */ }
            finally
            {
                if (protocol && !closed.get()) output.offer(json.createObjectNode().put("type", "error"));
            }
        }

        private boolean storageWithinBudget()
        {
            try (var paths = Files.walk(folder))
            {
                long bytes = 0;
                int files = 0;
                for (Path path : paths.limit(18).toList())
                {
                    if (path.equals(folder)) continue;
                    if (++files > 16 || Files.isSymbolicLink(path)) return false;
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) bytes += Files.size(path);
                    if (bytes > 1024 * 1024) return false;
                }
                return true;
            }
            catch (Exception error) { return closed.get(); }
        }

        private void close()
        {
            if (!closed.compareAndSet(false, true)) return;
            workers.remove(key, this);
            try { process.getOutputStream().close(); } catch (Exception ignored) { }
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroy();
            try { if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            output.offer(json.createObjectNode().put("type", "error"));
            try
            {
                removeFolder(folder);
            }
            catch (Exception error) { org.slf4j.LoggerFactory.getLogger(PiAgentRuntime.class).warn("框架临时目录回收失败，需检查任务存储目录"); }
        }
    }

    private static ServiceException unavailable()
    {
        return new ServiceException("执行框架未就绪、已中断或回执失效，本次运行停止；不会自动重放业务写入");
    }
}
