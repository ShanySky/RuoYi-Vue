package com.ruoyi.ai.runtime;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.ai.mapper.AiRunMapper;
import com.ruoyi.ai.mapper.AiPendingToolCallMapper;
import com.ruoyi.ai.mapper.AiServerCallMapper;

/** 单后端实例启动时收束旧进程事实；没有持久回执的写入始终保持未知。 */
@Component
public class AiRuntimeRecovery
{
    private final AiRunMapper runs;
    private final AiPendingToolCallMapper pending;
    private final AiServerCallMapper calls;

    public AiRuntimeRecovery(AiRunMapper runs, AiPendingToolCallMapper pending, AiServerCallMapper calls)
    {
        this.runs = runs;
        this.pending = pending;
        this.calls = calls;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recover()
    {
        calls.interruptAllExecuting();
        for (var run : runs.selectAllActive())
        {
            String target = "CANCEL_REQUESTED".equals(run.getStatus()) ? "CANCELLED" : "FAILED";
            runs.transition(run.getRunId(), run.getStatus(), target, "SERVER_PROCESS_RESTARTED");
            pending.cancelByRun(run.getRunId(), "CANCELLED");
        }
    }
}
