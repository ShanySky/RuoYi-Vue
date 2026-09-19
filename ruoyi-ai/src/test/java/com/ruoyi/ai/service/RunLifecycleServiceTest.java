package com.ruoyi.ai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.domain.AiRunStatus;
import com.ruoyi.ai.mapper.AiRunMapper;

class RunLifecycleServiceTest
{
    @Test
    void delegatesLegalTransitionThroughCompareAndSet()
    {
        AiRunMapper mapper = mock(AiRunMapper.class);
        AiRun run = run(7L, AiRunStatus.RUNNING);
        when(mapper.selectById(7L)).thenReturn(run);
        when(mapper.transition(7L, "RUNNING", "WAITING_TOOL", null)).thenReturn(1);

        RunLifecycleService lifecycle = new RunLifecycleService(mapper);
        assertTrue(lifecycle.tryWaitingTool(7L));
        verify(mapper).transition(7L, "RUNNING", "WAITING_TOOL", null);
    }

    @Test
    void rejectsIllegalTransitionWithoutDatabaseMutation()
    {
        AiRunMapper mapper = mock(AiRunMapper.class);
        when(mapper.selectById(9L)).thenReturn(run(9L, AiRunStatus.WAITING_TOOL));

        RunLifecycleService lifecycle = new RunLifecycleService(mapper);
        assertFalse(lifecycle.tryBeginCompaction(9L));
        verify(mapper, never()).transition(anyLong(), anyString(), anyString(), any());
    }

    @Test
    void cancellationIsIdempotentForTerminalCancelledRun()
    {
        AiRunMapper mapper = mock(AiRunMapper.class);
        when(mapper.selectById(11L)).thenReturn(run(11L, AiRunStatus.CANCELLED));

        RunLifecycleService lifecycle = new RunLifecycleService(mapper);
        assertTrue(lifecycle.requestCancel(11L, "USER_STOP"));
        verify(mapper, never()).transition(anyLong(), anyString(), anyString(), any());
    }

    private AiRun run(Long id, AiRunStatus status)
    {
        AiRun run = new AiRun();
        run.setRunId(id);
        run.setStatus(status.name());
        return run;
    }
}
