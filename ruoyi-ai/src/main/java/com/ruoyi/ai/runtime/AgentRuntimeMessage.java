package com.ruoyi.ai.runtime;

import java.util.List;

public record AgentRuntimeMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId,
        String toolName)
{
    public AgentRuntimeMessage
    {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static AgentRuntimeMessage system(String content)
    {
        return new AgentRuntimeMessage(Role.SYSTEM, content, List.of(), null, null);
    }

    public static AgentRuntimeMessage user(String content)
    {
        return new AgentRuntimeMessage(Role.USER, content, List.of(), null, null);
    }

    public static AgentRuntimeMessage assistant(String content)
    {
        return new AgentRuntimeMessage(Role.ASSISTANT, content, List.of(), null, null);
    }

    public static AgentRuntimeMessage assistant(String content, List<ToolCall> toolCalls)
    {
        return new AgentRuntimeMessage(Role.ASSISTANT, content, toolCalls, null, null);
    }

    public static AgentRuntimeMessage tool(String toolCallId, String toolName, String content)
    {
        return new AgentRuntimeMessage(Role.TOOL, content, List.of(), toolCallId, toolName);
    }

    public enum Role
    {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public record ToolCall(String id, String name, String arguments)
    {
    }
}
