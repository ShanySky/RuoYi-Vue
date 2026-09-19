package com.ruoyi.ai.service;

import org.springframework.stereotype.Service;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.domain.AiRunStatus;
import com.ruoyi.ai.mapper.AiRunMapper;

@Service
public class RunLifecycleService
{
    private final AiRunMapper runs;

    public RunLifecycleService(AiRunMapper runs)
    {
        this.runs = runs;
    }

    public boolean isRunnable(Long runId)
    {
        AiRun run = runs.selectById(runId);
        return run != null && AiRunStatus.from(run.getStatus()).isRunnable();
    }

    public boolean tryWaitingTool(Long runId)
    {
        return tryTransition(runId, AiRunStatus.WAITING_TOOL, null);
    }

    public boolean tryResumeTool(Long runId)
    {
        return tryTransition(runId, AiRunStatus.RUNNING, null);
    }

    public boolean tryBeginCompaction(Long runId)
    {
        return tryTransition(runId, AiRunStatus.COMPACTING, null);
    }

    public boolean tryEndCompaction(Long runId)
    {
        return tryTransition(runId, AiRunStatus.RUNNING, null);
    }

    public boolean tryComplete(Long runId)
    {
        return tryTransition(runId, AiRunStatus.COMPLETED, null);
    }

    public boolean tryFail(Long runId, String reason)
    {
        return tryTransition(runId, AiRunStatus.FAILED, reason);
    }

    public boolean requestCancel(Long runId, String reason)
    {
        AiRun current = runs.selectById(runId);
        if (current == null)
        {
            return false;
        }
        AiRunStatus status = AiRunStatus.from(current.getStatus());
        if (status == AiRunStatus.CANCEL_REQUESTED || status == AiRunStatus.CANCELLED)
        {
            return true;
        }
        return transition(current, AiRunStatus.CANCEL_REQUESTED, reason);
    }

    public boolean finishCancel(Long runId)
    {
        AiRun current = runs.selectById(runId);
        if (current == null)
        {
            return false;
        }
        AiRunStatus status = AiRunStatus.from(current.getStatus());
        if (status == AiRunStatus.CANCELLED)
        {
            return true;
        }
        return transition(current, AiRunStatus.CANCELLED, current.getCancelReason());
    }

    public boolean supersede(Long runId, Long newRunId)
    {
        AiRun current = runs.selectById(runId);
        if (current == null)
        {
            return false;
        }
        AiRunStatus from = AiRunStatus.from(current.getStatus());
        if (!from.canTransitionTo(AiRunStatus.SUPERSEDED))
        {
            return false;
        }
        return runs.supersedeFrom(runId, from.name(), newRunId) == 1;
    }

    public boolean tryTransition(Long runId, AiRunStatus target, String reason)
    {
        AiRun current = runs.selectById(runId);
        return current != null && transition(current, target, reason);
    }

    private boolean transition(AiRun current, AiRunStatus target, String reason)
    {
        AiRunStatus from = AiRunStatus.from(current.getStatus());
        if (!from.canTransitionTo(target))
        {
            return false;
        }
        return runs.transition(current.getRunId(), from.name(), target.name(), reason) == 1;
    }
}
