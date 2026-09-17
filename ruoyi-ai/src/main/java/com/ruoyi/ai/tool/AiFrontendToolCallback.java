package com.ruoyi.ai.tool;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Carries a frontend tool definition to the model. Execution intentionally happens in the browser.
 */
public class AiFrontendToolCallback implements ToolCallback
{
    private final ToolDefinition definition;

    public AiFrontendToolCallback(String name, String description, String inputSchema)
    {
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition()
    {
        return definition;
    }

    @Override
    public String call(String toolInput)
    {
        throw new UnsupportedOperationException("Frontend tools must be executed by the browser");
    }
}
