package com.ruoyi.ai.runtime;

public record AgentRuntimeUsage(long inputTokens, long cacheReadTokens, long cacheWriteTokens, long totalTokens)
{
    public static AgentRuntimeUsage empty()
    {
        return new AgentRuntimeUsage(0, 0, 0, 0);
    }
}
