package com.ruoyi.ai.dto;

import java.util.List;
import java.util.Map;

public class AiChatTurnRequest
{
    private Long conversationId;
    private Long modelId;
    private String userMessage;
    private AiToolResultRequest toolResult;
    private String route;
    private Map<String, Object> pageContext;
    private List<AiFrontendToolDefinition> frontendTools;

    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public AiToolResultRequest getToolResult() { return toolResult; }
    public void setToolResult(AiToolResultRequest toolResult) { this.toolResult = toolResult; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public Map<String, Object> getPageContext() { return pageContext; }
    public void setPageContext(Map<String, Object> pageContext) { this.pageContext = pageContext; }
    public List<AiFrontendToolDefinition> getFrontendTools() { return frontendTools; }
    public void setFrontendTools(List<AiFrontendToolDefinition> frontendTools) { this.frontendTools = frontendTools; }
}
