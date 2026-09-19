package com.ruoyi.ai.runtime;

import java.util.List;

public record AgentRuntimeRequest(Long modelId, String reasoningEffort, String promptCacheKey,
        List<AgentRuntimeMessage> messages, List<AgentRuntimeTool> tools)
{
    public AgentRuntimeRequest
    {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
