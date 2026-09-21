package com.ruoyi.ai.runtime;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 独立安排进程回收和租约，避免文件服务超时阻塞模型停止。 */
@Configuration("aiRuntimeSchedulingConfiguration")
public class AiRuntimeScheduler
{
    @Bean public ThreadPoolTaskScheduler aiRuntimeScheduler()
    {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ai-runtime-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
