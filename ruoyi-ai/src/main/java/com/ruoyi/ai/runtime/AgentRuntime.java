package com.ruoyi.ai.runtime;

public interface AgentRuntime
{
    AgentRuntimeResult call(AgentRuntimeRequest request) throws Exception;
}
