package com.ruoyi.ai.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.alibaba.druid.pool.DruidDataSource;

/** 只借用本项目主库连接，不接受模型指定连接或凭据。 */
@Component
public class AiDataConnection
{
    private final DataSource source;
    private final Semaphore capacity = new Semaphore(8);

    public AiDataConnection(@Qualifier("masterDataSource") DataSource source) { this.source = source; }

    public Lease borrow() throws SQLException
    {
        if (!(source instanceof DruidDataSource druid)) throw new SQLException("当前数据源不支持有界借用");
        if (!capacity.tryAcquire()) throw new SQLException("数据查询并发已达全局上限");
        Connection connection = null;
        try
        {
            connection = druid.getConnection(1000);
            return new Lease(connection);
        }
        catch (SQLException | RuntimeException error)
        {
            try { if (connection != null) connection.close(); }
            catch (SQLException closeError) { error.addSuppressed(closeError); }
            finally { capacity.release(); }
            throw error;
        }
    }

    public final class Lease implements AutoCloseable
    {
        private final Connection connection;
        private final int networkTimeout;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(Connection connection) throws SQLException
        {
            this.connection = connection;
            this.networkTimeout = connection.getNetworkTimeout();
            connection.setNetworkTimeout(Runnable::run, 4000);
        }
        public Connection connection() { return connection; }
        @Override public void close() throws SQLException
        {
            if (closed.compareAndSet(false, true))
            {
                try
                {
                    try { connection.setNetworkTimeout(Runnable::run, networkTimeout); }
                    catch (SQLException error)
                    {
                        try { connection.abort(Runnable::run); } catch (SQLException abortError) { error.addSuppressed(abortError); }
                        throw error;
                    }
                }
                finally { try { connection.close(); } finally { capacity.release(); } }
            }
        }
    }
}
