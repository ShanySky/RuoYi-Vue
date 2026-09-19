package com.ruoyi.ai.tool;

import org.springframework.stereotype.Component;

@Component
public class ToolPolicyRegistry
{
    private final SystemToolPolicyProvider system;
    private final MonitorToolPolicyProvider monitor;
    private final GeneratorToolPolicyProvider generator;
    private final GenericCrudToolPolicyProvider genericCrud;

    public ToolPolicyRegistry(SystemToolPolicyProvider system, MonitorToolPolicyProvider monitor,
            GeneratorToolPolicyProvider generator, GenericCrudToolPolicyProvider genericCrud)
    {
        this.system = system;
        this.monitor = monitor;
        this.generator = generator;
        this.genericCrud = genericCrud;
    }

    public ToolPolicyDefinition resolve(String toolName)
    {
        ToolPolicyDefinition policy = system.resolve(toolName);
        if (policy == null) policy = monitor.resolve(toolName);
        if (policy == null) policy = generator.resolve(toolName);
        if (policy == null) policy = genericCrud.resolve(toolName);
        return policy;
    }

    public ToolPolicyDefinition require(String toolName)
    {
        ToolPolicyDefinition policy = resolve(toolName);
        if (policy == null)
        {
            throw new IllegalArgumentException("Unsupported frontend tool: " + toolName);
        }
        return policy;
    }
}
