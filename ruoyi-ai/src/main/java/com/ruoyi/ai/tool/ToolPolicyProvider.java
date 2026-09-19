package com.ruoyi.ai.tool;

public interface ToolPolicyProvider
{
    ToolPolicyDefinition resolve(String toolName);
}
