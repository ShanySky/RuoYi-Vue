package com.ruoyi.ai.runtime;

import java.util.List;

public record AgentRuntimeResult(Long modelId, String modelCode, String text, List<ToolCall> toolCalls,
        AgentRuntimeUsage usage, String finishReason)
{
    public AgentRuntimeResult
    {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? AgentRuntimeUsage.empty() : usage;
    }

    public boolean hasToolCalls()
    {
        return !toolCalls.isEmpty();
    }

    public record ToolCall(String id, String name, String arguments)
    {
    }
}
