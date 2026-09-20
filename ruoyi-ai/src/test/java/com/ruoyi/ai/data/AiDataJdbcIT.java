package com.ruoyi.ai.data;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.alibaba.druid.pool.DruidDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.mapper.AiRunMapper;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式选择执行的真实 JDBC 验收；只允许连接本任务专用数据库。 */
class AiDataJdbcIT
{
    private static DruidDataSource pool;
    private static String url, password;
    private AiDataConnection connections;
    private AiDataExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @BeforeAll static void connect() throws Exception
    {
        url = System.getenv("AI_DATA_JDBC_URL");
        password = System.getenv("AI_DATA_JDBC_PASSWORD");
        assertNotNull(url, "真实 JDBC 验收必须显式提供本任务连接");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:13392/ruoyi_ai_evolution?"));
        assertNotNull(password);
        pool = new DruidDataSource();
        pool.setUrl(url);
        pool.setDriverClassName("com.mysql.cj.jdbc.Driver");
        pool.setUsername("root");
        pool.setPassword(password);
        pool.setMaxActive(12);
        pool.setMinIdle(0);
        pool.setMaxWait(1000);
        pool.setConnectTimeout(2000);
        pool.setSocketTimeout(5000);
        pool.init();
    }

    @BeforeEach void setup()
    {
        connections = new AiDataConnection(pool);
        var runs = mock(AiRunMapper.class);
        when(runs.selectById(anyLong())).thenAnswer(invocation -> {
            AiRun run = new AiRun();
            run.setRunId(invocation.getArgument(0));
            run.setUserId(1L);
            run.setStatus(running.get() ? "WAITING_TOOL" : "CANCELLED");
            return run;
        });
        executor = new AiDataExecutor(connections, runs, new ObjectMapper());
    }

    @AfterEach void stop() { executor.shutdown(); }
    @AfterAll static void close() { if (pool != null) pool.close(); }

    @Test void realReadOnlyTransactionRejectsWritesAndResultBytesAreBounded() throws Exception
    {
        try (var lease = connections.borrow())
        {
            var connection = lease.connection();
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement())
            {
                statement.execute("START TRANSACTION READ ONLY");
                var denied = assertThrows(java.sql.SQLException.class,
                        () -> statement.executeUpdate("update sys_post set post_sort=post_sort where 1=0"));
                assertEquals(1792, denied.getErrorCode());
            }
            finally { connection.rollback(); connection.setAutoCommit(true); }
        }
        var overflow = query("select repeat('x',131073) as content", "content");
        assertTrue(assertThrows(ServiceException.class, () -> executor.execute(1L, 1L, overflow)).getMessage().contains("128 KiB"));
        var result = executor.execute(1L, 1L, query("select '可用' as content", "content"));
        assertEquals("可用", result.path("rows").get(0).path("content").asText());
        assertEquals(0, pool.getActiveCount());
        try (var reused = pool.getConnection())
        {
            assertTrue(reused.getAutoCommit());
            assertFalse(reused.isReadOnly());
            assertEquals(5000, reused.getNetworkTimeout(), "任务网络限制不能遗留到普通业务连接");
        }
    }

    @Test void realDatabaseStatementTimesOutAndDoesNotRemainInProcessList() throws Exception
    {
        String marker = "ai_data_guard_timeout";
        String statement = "select /*+ MAX_EXECUTION_TIME(1500) */ /* " + marker + " */ count(*) as metric_1 "
                + "from information_schema.columns a cross join information_schema.columns b cross join information_schema.columns c";
        var workers = Executors.newSingleThreadExecutor();
        long start = System.nanoTime();
        try
        {
            var future = workers.submit(() -> assertThrows(ServiceException.class, () -> executor.execute(1L, 2L, query(statement, "metric_1"))));
            awaitProcesses(marker, 1, 1200);
            future.get(5, TimeUnit.SECONDS);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5000);
            awaitProcesses(marker, 0, 2000);
            assertEquals(0, pool.getActiveCount());
        }
        finally { workers.shutdownNow(); }
    }

    @Test void perUserConcurrencyAndStoppingCancelActualQueries() throws Exception
    {
        String marker = "ai_data_guard_stop";
        var query = query("select /* " + marker + " */ sleep(10) as waiting", "waiting");
        var workers = Executors.newFixedThreadPool(2);
        try
        {
            var first = workers.submit(() -> assertThrows(ServiceException.class, () -> executor.execute(1L, 3L, query)));
            var second = workers.submit(() -> assertThrows(ServiceException.class, () -> executor.execute(1L, 4L, query)));
            awaitProcesses(marker, 2, 1500);
            assertTrue(assertThrows(ServiceException.class, () -> executor.execute(1L, 5L, query)).getMessage().contains("本人"));
            running.set(false);
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
            awaitProcesses(marker, 0, 1500);
            assertEquals(0, pool.getActiveCount());
        }
        finally { workers.shutdownNow(); }
    }

    @Test void globalConnectionCeilingAppliesToActualPoolBorrowing() throws Exception
    {
        var leases = new ArrayList<AiDataConnection.Lease>();
        try
        {
            for (int count = 0; count < 8; count++) leases.add(connections.borrow());
            assertEquals(8, pool.getActiveCount());
            assertThrows(java.sql.SQLException.class, connections::borrow);
        }
        finally { for (var lease : leases) lease.close(); }
        assertEquals(0, pool.getActiveCount());
    }

    private static AiDataQuery.Compiled query(String sql, String column)
    {
        return new AiDataQuery.Compiled(sql, List.of(), 1, 0, List.of(column));
    }

    private static void awaitProcesses(String marker, int expected, long timeout) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        int found = -1;
        try (var observer = DriverManager.getConnection(url, "root", password);
                var statement = observer.prepareStatement("select count(*) from information_schema.processlist where id<>connection_id() and info like ?"))
        {
            statement.setString(1, "%" + marker + "%");
            do
            {
                try (var rows = statement.executeQuery()) { rows.next(); found = rows.getInt(1); }
                if (found == expected) return;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
        }
        assertEquals(expected, found, "数据库中实际执行语句数量不符合预期");
    }
}
