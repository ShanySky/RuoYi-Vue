package com.ruoyi.ai.data;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.ai.server.AiApiCatalog;
import com.ruoyi.common.annotation.DataScope;
import com.ruoyi.common.core.domain.BaseEntity;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.aspectj.DataScopeAspect;
import com.ruoyi.system.domain.SysPost;
import com.ruoyi.system.service.impl.SysDeptServiceImpl;
import com.ruoyi.system.service.impl.SysPostServiceImpl;
import com.ruoyi.system.service.impl.SysUserServiceImpl;

/** 显式绑定已核查的原查询授权；字段、语句和数据库结构均从真实来源派生。 */
@Component
public class AiDataCatalog
{
    public record Binding(String id, String title, String path, String statement, Class<?> service,
            String method, Supplier<? extends BaseEntity> parameter, boolean scoped, Set<String> tables) { }
    public record Field(String name, String table, String column, String type, int jdbcType) { }
    public record View(Binding binding, AiApiCatalog.Capability api, List<Field> fields, String fingerprint) {
        public String id() { return binding.id(); }
    }
    public record Column(String name, String type, String comment, boolean supported) { }
    public record Table(String name, String comment, List<Column> columns, String fingerprint, boolean supported) { }
    public record Snapshot(String database, String fingerprint, List<Table> tables, List<View> views) { }

    private static final List<Binding> BINDINGS = List.of(
        new Binding("data_users", "用户及所属部门", "/system/user/list", "com.ruoyi.system.mapper.SysUserMapper.selectUserList",
            SysUserServiceImpl.class, "selectUserList", SysUser::new, true, Set.of("sys_user", "sys_dept")),
        new Binding("data_departments", "部门", "/system/dept/list", "com.ruoyi.system.mapper.SysDeptMapper.selectDeptList",
            SysDeptServiceImpl.class, "selectDeptList", SysDept::new, true, Set.of("sys_dept")),
        new Binding("data_posts", "岗位", "/system/post/list", "com.ruoyi.system.mapper.SysPostMapper.selectPostList",
            SysPostServiceImpl.class, "selectPostList", SysPost::new, false, Set.of("sys_post")));
    private final AiDataConnection connections;
    private final SqlSessionFactory sessions;
    private final AiApiCatalog apis;

    public AiDataCatalog(AiDataConnection connections, SqlSessionFactory sessions, AiApiCatalog apis)
    {
        this.connections = connections;
        this.sessions = sessions;
        this.apis = apis;
    }

    public Snapshot snapshot()
    {
        try (var lease = connections.borrow())
        {
            Connection connection = lease.connection();
            if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())) throw denied();
            String database = connection.getCatalog();
            if (database == null || database.isBlank()) throw denied();
            List<View> views = new ArrayList<>();
            var capabilities = apis.all();
            for (Binding binding : BINDINGS)
            {
                var api = capabilities.stream().filter(value -> value.path().equals(binding.path())
                        && value.method().equals("GET") && value.supported()).findFirst().orElseThrow(AiDataCatalog::denied);
                SysUser unrestricted = new SysUser(1L);
                String source = sourceSql(binding, unrestricted, api.permission());
                List<Field> fields = new ArrayList<>();
                // 原查询直接取零行元数据；包装成派生表会让当前驱动把物理表名改成 SQL 别名。
                try (var statement = connection.prepareStatement("select /*+ MAX_EXECUTION_TIME(1500) */ " + source.substring(6) + " limit 0"))
                {
                    statement.setQueryTimeout(3);
                    try (ResultSet result = statement.executeQuery())
                    {
                        var metadata = result.getMetaData();
                        for (int index = 1; index <= metadata.getColumnCount(); index++)
                        {
                            String table = metadata.getTableName(index);
                            String name = metadata.getColumnLabel(index);
                            String column = metadata.getColumnName(index);
                            if (!identifier(name) || !identifier(column) || !binding.tables().contains(table)
                                    || !database.equals(metadata.getCatalogName(index))
                                    || fields.stream().anyMatch(field -> field.name().equals(name))) throw denied();
                            int jdbcType = metadata.getColumnType(index);
                            fields.add(new Field(name, table, column, type(jdbcType), jdbcType));
                        }
                        if (result.next() || fields.isEmpty()) throw denied();
                    }
                }
                String fingerprint = AiApiCatalog.hash(source + ":" + scopeContract(scope(binding)) + ":" + api.fingerprint() + ":" + fields);
                views.add(new View(binding, api, List.copyOf(fields), fingerprint));
            }
            List<Table> tables = new ArrayList<>();
            Map<String, List<Column>> columns = new LinkedHashMap<>();
            Map<String, String> comments = new LinkedHashMap<>();
            try (var statement = connection.prepareStatement("select c.table_name,c.column_name,c.column_type,c.column_comment,t.table_comment "
                    + "from information_schema.columns c join information_schema.tables t on t.table_schema=c.table_schema "
                    + "and t.table_name=c.table_name where c.table_schema=? and t.table_type='BASE TABLE' order by c.table_name,c.ordinal_position"))
            {
                statement.setString(1, database);
                statement.setMaxRows(20001);
                statement.setQueryTimeout(3);
                try (var rows = statement.executeQuery())
                {
                    int count = 0;
                    while (rows.next())
                    {
                        if (++count > 20000) throw denied();
                        String table = rows.getString(1), column = rows.getString(2);
                        boolean supported = views.stream().flatMap(view -> view.fields().stream())
                                .anyMatch(field -> field.table().equals(table) && field.column().equals(column));
                        columns.computeIfAbsent(table, ignored -> new ArrayList<>()).add(
                                new Column(column, rows.getString(3), rows.getString(4), supported));
                        comments.put(table, rows.getString(5));
                    }
                }
            }
            columns.forEach((name, values) -> {
                var related = views.stream().filter(view -> view.binding().tables().contains(name)).map(View::fingerprint).toList();
                tables.add(new Table(name, comments.get(name), List.copyOf(values),
                        AiApiCatalog.hash(database + ":" + name + ":" + values + ":" + related), !related.isEmpty()));
            });
            return new Snapshot(database, AiApiCatalog.hash("MySQL:" + database), List.copyOf(tables), List.copyOf(views));
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw new ServiceException("当前数据库结构或授权映射不可用，查询能力关闭"); }
    }

    public String sourceSql(Binding binding, SysUser user, String permission)
    {
        BaseEntity parameter = binding.parameter().get();
        parameter.getParams().put(DataScopeAspect.DATA_SCOPE, "");
        DataScope scope = scope(binding);
        if (binding.scoped() != (scope != null)) throw denied();
        if (scope != null && !user.isAdmin())
        {
            if (user.getRoles() == null || user.getUserId() == null || user.getDeptId() == null) throw denied();
            if (user.getRoles().stream().anyMatch(role -> !Set.of("1", "2", "3", "4", "5").contains(role.getDataScope()))) throw denied();
            String required = scope.permission().isBlank() ? permission : scope.permission();
            DataScopeAspect.dataScopeFilter(parameter, user, scope.userAlias(), scope.deptAlias(), scope.userField(), scope.deptField(), required);
        }
        var mapped = sessions.getConfiguration().getMappedStatement(binding.statement());
        var sql = mapped.getBoundSql(parameter);
        if (!sql.getParameterMappings().isEmpty()) throw denied();
        String source = sql.getSql().trim();
        if (!source.toLowerCase(java.util.Locale.ROOT).startsWith("select ") || source.contains(";")) throw denied();
        return source;
    }

    private static DataScope scope(Binding binding)
    {
        try { return binding.service().getMethod(binding.method(), binding.parameter().get().getClass()).getAnnotation(DataScope.class); }
        catch (ReflectiveOperationException error) { throw denied(); }
    }

    static String scopeContract(DataScope scope)
    {
        // 注解的 toString 成员顺序在不同 JVM 中不稳定，不能作为持久契约事实。
        return scope == null ? "GLOBAL" : List.of(scope.deptAlias(), scope.deptField(), scope.userAlias(),
                scope.userField(), scope.permission()).toString();
    }

    public static boolean identifier(String value) { return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"); }

    private static String type(int jdbc)
    {
        return switch (jdbc)
        {
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.TINYINT, Types.DECIMAL, Types.NUMERIC,
                    Types.DOUBLE, Types.FLOAT, Types.REAL -> "number";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.DATE, Types.TIME, Types.TIMESTAMP -> "string";
            default -> throw denied();
        };
    }

    private static ServiceException denied() { return new ServiceException("业务查询的字段或数据范围无法可靠映射，暂不开放"); }
}
