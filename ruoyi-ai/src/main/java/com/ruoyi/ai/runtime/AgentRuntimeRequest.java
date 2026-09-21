package com.ruoyi.ai.runtime;

import java.util.List;

public record AgentRuntimeRequest(Long modelId, String reasoningEffort, String promptCacheKey,
        List<AgentRuntimeMessage> messages, List<AgentRuntimeTool> tools, Long runId, boolean standalone)
{
    public AgentRuntimeRequest(Long modelId, String reasoningEffort, String promptCacheKey,
            List<AgentRuntimeMessage> messages, List<AgentRuntimeTool> tools)
    {
        this(modelId, reasoningEffort, promptCacheKey, messages, tools, null, true);
    }

    public AgentRuntimeRequest
    {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
