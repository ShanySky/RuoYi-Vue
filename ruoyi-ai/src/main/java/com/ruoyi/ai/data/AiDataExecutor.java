package com.ruoyi.ai.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.mapper.AiRunMapper;
import com.ruoyi.common.exception.ServiceException;

/** 限制实际数据库执行与返回；共享连接账号不参与用户授权裁决。 */
@Service
public class AiDataExecutor
{
    private final AiDataConnection connections;
    private final AiRunMapper runs;
    private final ObjectMapper json;
    private final Map<Long, Integer> users = new HashMap<>();
    private final Set<Statement> executing = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService watchdog = Executors.newScheduledThreadPool(2);

    public AiDataExecutor(AiDataConnection connections, AiRunMapper runs, ObjectMapper json)
    {
        this.connections = connections;
        this.runs = runs;
        this.json = json;
    }

    public JsonNode execute(Long userId, Long runId, AiDataQuery.Compiled query)
    {
        enter(userId);
        try (var lease = connections.borrow())
        {
            requireRunning(userId, runId);
            Connection connection = lease.connection();
            boolean autoCommit = connection.getAutoCommit(), readOnly = connection.isReadOnly();
            int networkTimeout = connection.getNetworkTimeout();
            try
            {
                connection.setNetworkTimeout(Runnable::run, 4000);
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try (Statement control = connection.createStatement()) { control.execute("START TRANSACTION READ ONLY"); }
                try (PreparedStatement statement = connection.prepareStatement(query.sql()))
                {
                    statement.setQueryTimeout(3);
                    statement.setMaxRows(query.limit() + 1);
                    for (int index = 0; index < query.parameters().size(); index++) statement.setObject(index + 1, query.parameters().get(index));
                    executing.add(statement);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    var watcher = watchdog.scheduleAtFixedRate(() -> {
                        try
                        {
                            requireRunning(userId, runId);
                            if (System.nanoTime() > deadline) cancel(statement);
                        }
                        catch (Exception error) { cancel(statement); }
                    }, 100, 200, TimeUnit.MILLISECONDS);
                    try (var result = statement.executeQuery())
                    {
                        var metadata = result.getMetaData();
                        if (metadata.getColumnCount() != query.columns().size()) throw rejected();
                        var rows = new ArrayList<Map<String, Object>>();
                        boolean more = false;
                        int bytes = 0;
                        while (result.next())
                        {
                            requireRunning(userId, runId);
                            if (rows.size() == query.limit()) { more = true; break; }
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int index = 1; index <= metadata.getColumnCount(); index++)
                            {
                                if (!query.columns().get(index - 1).equals(metadata.getColumnLabel(index))) throw rejected();
                                Object value = result.getObject(index);
                                if (value instanceof java.time.temporal.TemporalAccessor || value instanceof java.util.Date) value = value.toString();
                                row.put(query.columns().get(index - 1), value);
                            }
                            bytes += json.writeValueAsBytes(row).length;
                            if (bytes > 128 * 1024) throw new ServiceException("查询结果超过 128 KiB，请缩小字段或行数");
                            rows.add(row);
                        }
                        requireRunning(userId, runId);
                        Map<String, Object> output = Map.of("code", 200, "rows", rows, "columns", query.columns(),
                                "count", rows.size(), "hasMore", more, "offset", query.offset(), "nextOffset", query.offset() + rows.size());
                        if (json.writeValueAsBytes(output).length > 128 * 1024) throw new ServiceException("查询结果超过 128 KiB，请缩小字段或行数");
                        return json.valueToTree(output);
                    }
                    finally { watcher.cancel(false); executing.remove(statement); }
                }
            }
            finally
            {
                restore(connection, autoCommit, readOnly, networkTimeout);
            }
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("查询超时、已停止或数据库不可用，本次结果未交付"); }
        finally { leave(userId); }
    }

    private void requireRunning(Long userId, Long runId)
    {
        var run = runs.selectById(runId);
        if (run == null || !userId.equals(run.getUserId()) || !"WAITING_TOOL".equals(run.getStatus())) throw rejected();
    }

    private synchronized void enter(Long user)
    {
        if (users.getOrDefault(user, 0) >= 2) throw new ServiceException("本人数据查询并发已达上限");
        users.merge(user, 1, Integer::sum);
    }

    private synchronized void leave(Long user)
    {
        users.computeIfPresent(user, (key, count) -> count == 1 ? null : count - 1);
    }

    private static void cancel(Statement statement)
    {
        try { statement.cancel(); }
        catch (Exception ignored) { /* 驱动超时与连接关闭继续约束未成功取消的语句。 */ }
    }

    private static void restore(Connection connection, boolean autoCommit, boolean readOnly, int networkTimeout) throws java.sql.SQLException
    {
        java.sql.SQLException failure = null;
        try { connection.rollback(); } catch (java.sql.SQLException error) { failure = error; }
        try { connection.setAutoCommit(autoCommit); } catch (java.sql.SQLException error) { failure = error; }
        try { connection.setReadOnly(readOnly); } catch (java.sql.SQLException error) { failure = error; }
        try { connection.setNetworkTimeout(Runnable::run, networkTimeout); } catch (java.sql.SQLException error) { failure = error; }
        if (failure != null)
        {
            // 无法恢复的物理连接不得带着任务超时/只读设置回到普通业务连接池。
            try { connection.abort(Runnable::run); } catch (java.sql.SQLException error) { failure.addSuppressed(error); }
            throw failure;
        }
    }

    private static ServiceException rejected() { return new ServiceException("运行已停止或查询结果结构发生变化"); }

    @PreDestroy public void shutdown()
    {
        executing.forEach(AiDataExecutor::cancel);
        watchdog.shutdownNow();
    }
}
