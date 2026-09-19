package com.ruoyi.ai.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import static com.ruoyi.ai.tool.ToolPolicyDefinition.objectSchema;
import static com.ruoyi.ai.tool.ToolPolicyDefinition.flexibleObjectSchema;

@Component
public class MonitorToolPolicyProvider implements ToolPolicyProvider
{
    private final Map<String, ToolPolicyDefinition> policies;

    public MonitorToolPolicyProvider()
    {
        Map<String, ToolPolicyDefinition> values = new LinkedHashMap<>();
        values.put("page_monitor_job_view", new ToolPolicyDefinition(
                        "READ", "monitor:job:query", "查看指定定时任务的详细配置",
                        objectSchema(Map.of("jobId", Map.of("type", "integer", "description", "任务ID")), List.of("jobId"))));
        values.put("page_monitor_job_change_status", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "monitor:job:changeStatus", "启用或停用指定定时任务；会改变调度器实际运行状态",
                        objectSchema(Map.of(
                                "jobId", Map.of("type", "integer", "description", "任务ID"),
                                "status", Map.of("type", "string", "enum", List.of("0", "1"), "description", "0正常，1暂停")),
                                List.of("jobId", "status"))));
        values.put("page_monitor_job_run_now", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "monitor:job:changeStatus", "立即执行一次指定定时任务；该动作可能触发实际业务副作用",
                        objectSchema(Map.of(
                                "jobId", Map.of("type", "integer", "description", "任务ID"),
                                "jobGroup", Map.of("type", "string", "description", "任务组")),
                                List.of("jobId", "jobGroup"))));
        values.put("page_monitor_logininfor_unlock", new ToolPolicyDefinition(
                        "WRITE", "monitor:logininfor:unlock", "解除指定用户的登录锁定状态",
                        objectSchema(Map.of("userName", Map.of("type", "string", "description", "登录账号")), List.of("userName"))));
        values.put("page_monitor_logininfor_clean", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "monitor:logininfor:remove", "清空全部登录日志；该动作不可逆",
                        objectSchema(Map.of(), List.of())));
        values.put("page_monitor_online_force_logout", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "monitor:online:forceLogout", "强制指定在线会话退出登录",
                        objectSchema(Map.of("tokenId", Map.of("type", "string", "description", "在线会话 Token ID")), List.of("tokenId"))));
        values.put("page_monitor_operlog_view", new ToolPolicyDefinition(
                        "READ", "monitor:operlog:query", "查看指定操作日志详情",
                        objectSchema(Map.of("operId", Map.of("type", "integer", "description", "操作日志ID")), List.of("operId"))));
        values.put("page_monitor_cache_list_keys", new ToolPolicyDefinition(
                        "READ", "monitor:cache:list", "列出指定缓存名称下的键名",
                        objectSchema(Map.of("cacheName", Map.of("type", "string")), List.of("cacheName"))));
        values.put("page_monitor_cache_list_view_value", new ToolPolicyDefinition(
                        "READ", "monitor:cache:list", "查看指定缓存键当前值",
                        objectSchema(Map.of(
                                "cacheName", Map.of("type", "string"),
                                "cacheKey", Map.of("type", "string")), List.of("cacheName", "cacheKey"))));
        values.put("page_monitor_cache_list_clear_name", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "monitor:cache:list", "清理指定缓存名称下全部键；可能影响登录、配置或业务运行状态",
                        objectSchema(Map.of("cacheName", Map.of("type", "string")), List.of("cacheName"))));
        values.put("page_monitor_cache_list_clear_key", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "monitor:cache:list", "删除指定 Redis 缓存键；可能影响业务运行状态",
                        objectSchema(Map.of("cacheKey", Map.of("type", "string")), List.of("cacheKey"))));
        values.put("page_monitor_cache_list_clear_all", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "monitor:cache:list", "清理当前 Redis 数据库全部缓存键；高风险且可能使登录会话失效",
                        objectSchema(Map.of(), List.of())));
        values.put("page_monitor_operlog_clean", new ToolPolicyDefinition(
                        "DANGEROUS_WRITE", "monitor:operlog:remove", "清空全部操作日志；该动作不可逆",
                        objectSchema(Map.of(), List.of())));

        policies = Map.copyOf(values);
    }

    @Override
    public ToolPolicyDefinition resolve(String toolName)
    {
        return policies.get(toolName);
    }
}
