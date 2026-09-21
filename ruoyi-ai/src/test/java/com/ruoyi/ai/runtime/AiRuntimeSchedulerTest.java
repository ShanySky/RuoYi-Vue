package com.ruoyi.ai.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AiRuntimeSchedulerTest
{
    @Test void 配置和实例名称独立且能在禁止覆盖时启动()
    {
        try (var context = new AnnotationConfigApplicationContext())
        {
            context.setAllowBeanDefinitionOverriding(false);
            context.register(AiRuntimeScheduler.class);
            context.refresh();
            var scheduler = context.getBean("aiRuntimeScheduler", ThreadPoolTaskScheduler.class);
            assertEquals(4, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
        }
    }
}
