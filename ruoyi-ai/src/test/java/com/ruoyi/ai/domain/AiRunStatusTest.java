package com.ruoyi.ai.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class AiRunStatusTest
{
    @Test
    void allowsExpectedActiveTransitions()
    {
        assertTrue(AiRunStatus.RUNNING.canTransitionTo(AiRunStatus.WAITING_TOOL));
        assertTrue(AiRunStatus.RUNNING.canTransitionTo(AiRunStatus.COMPACTING));
        assertTrue(AiRunStatus.RUNNING.canTransitionTo(AiRunStatus.COMPLETED));
        assertTrue(AiRunStatus.WAITING_TOOL.canTransitionTo(AiRunStatus.RUNNING));
        assertTrue(AiRunStatus.COMPACTING.canTransitionTo(AiRunStatus.RUNNING));

        for (AiRunStatus status : new AiRunStatus[] {
                AiRunStatus.RUNNING, AiRunStatus.WAITING_TOOL, AiRunStatus.COMPACTING })
        {
            assertTrue(status.canTransitionTo(AiRunStatus.CANCEL_REQUESTED));
            assertTrue(status.canTransitionTo(AiRunStatus.SUPERSEDED));
            assertTrue(status.canTransitionTo(AiRunStatus.FAILED));
        }
        assertTrue(AiRunStatus.CANCEL_REQUESTED.canTransitionTo(AiRunStatus.CANCELLED));
        assertTrue(AiRunStatus.CANCEL_REQUESTED.canTransitionTo(AiRunStatus.SUPERSEDED));
    }

    @Test
    void rejectsIllegalAndTerminalTransitions()
    {
        assertFalse(AiRunStatus.WAITING_TOOL.canTransitionTo(AiRunStatus.COMPACTING));
        assertFalse(AiRunStatus.COMPACTING.canTransitionTo(AiRunStatus.WAITING_TOOL));
        assertFalse(AiRunStatus.CANCEL_REQUESTED.canTransitionTo(AiRunStatus.RUNNING));
        assertFalse(AiRunStatus.RUNNING.canTransitionTo(AiRunStatus.RUNNING));

        for (AiRunStatus terminal : new AiRunStatus[] {
                AiRunStatus.CANCELLED, AiRunStatus.SUPERSEDED, AiRunStatus.COMPLETED, AiRunStatus.FAILED })
        {
            for (AiRunStatus target : AiRunStatus.values())
            {
                assertFalse(terminal.canTransitionTo(target));
            }
        }
    }
}
