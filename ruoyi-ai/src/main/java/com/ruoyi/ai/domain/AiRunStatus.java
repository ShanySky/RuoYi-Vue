package com.ruoyi.ai.domain;

import java.util.EnumSet;
import java.util.Set;
import com.ruoyi.common.exception.ServiceException;

public enum AiRunStatus
{
    RUNNING,
    WAITING_TOOL,
    COMPACTING,
    CANCEL_REQUESTED,
    CANCELLED,
    SUPERSEDED,
    COMPLETED,
    FAILED;

    private static final Set<AiRunStatus> ACTIVE = EnumSet.of(RUNNING, WAITING_TOOL, COMPACTING, CANCEL_REQUESTED);
    private static final Set<AiRunStatus> TERMINAL = EnumSet.of(CANCELLED, SUPERSEDED, COMPLETED, FAILED);

    public boolean isActive()
    {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal()
    {
        return TERMINAL.contains(this);
    }

    public boolean isRunnable()
    {
        return this == RUNNING || this == COMPACTING;
    }

    public boolean canTransitionTo(AiRunStatus target)
    {
        if (target == null || target == this || isTerminal())
        {
            return false;
        }
        return switch (this)
        {
            case RUNNING -> target == WAITING_TOOL || target == COMPACTING || target == COMPLETED
                    || target == CANCEL_REQUESTED || target == SUPERSEDED || target == FAILED;
            case WAITING_TOOL -> target == RUNNING || target == CANCEL_REQUESTED || target == SUPERSEDED
                    || target == FAILED;
            case COMPACTING -> target == RUNNING || target == CANCEL_REQUESTED || target == SUPERSEDED
                    || target == FAILED;
            case CANCEL_REQUESTED -> target == CANCELLED || target == SUPERSEDED;
            default -> false;
        };
    }

    public static AiRunStatus from(String value)
    {
        if (value == null)
        {
            throw new ServiceException("Run 状态为空");
        }
        try
        {
            return valueOf(value);
        }
        catch (IllegalArgumentException e)
        {
            throw new ServiceException("未知 Run 状态：" + value);
        }
    }
}
