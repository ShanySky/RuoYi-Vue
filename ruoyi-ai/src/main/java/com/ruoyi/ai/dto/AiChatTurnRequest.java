package com.ruoyi.ai.dto;

import java.util.List;
import java.util.Map;

public class AiChatTurnRequest
{
    private Long conversationId;
    private String clientRunKey;
    private Long modelId;
    private String reasoningEffort;
    private String userMessage;
    private AiToolResultRequest toolResult;
    private String capabilityProtocol;
    private String pageId;
    private String route;
    private String pageInstanceId;
    private Long pageVersion;
    private Map<String, Object> pageContext;
    private List<AiFrontendToolDefinition> frontendTools;

    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getClientRunKey() { return clientRunKey; }
    public void setClientRunKey(String clientRunKey) { this.clientRunKey = clientRunKey; }
    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public AiToolResultRequest getToolResult() { return toolResult; }
    public void setToolResult(AiToolResultRequest toolResult) { this.toolResult = toolResult; }
    public String getCapabilityProtocol() { return capabilityProtocol; }
    public void setCapabilityProtocol(String capabilityProtocol) { this.capabilityProtocol = capabilityProtocol; }
    public String getPageId() { return pageId; }
    public void setPageId(String pageId) { this.pageId = pageId; }
    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }
    public String getPageInstanceId() { return pageInstanceId; }
    public void setPageInstanceId(String pageInstanceId) { this.pageInstanceId = pageInstanceId; }
    public Long getPageVersion() { return pageVersion; }
    public void setPageVersion(Long pageVersion) { this.pageVersion = pageVersion; }
    public Map<String, Object> getPageContext() { return pageContext; }
    public void setPageContext(Map<String, Object> pageContext) { this.pageContext = pageContext; }
    public List<AiFrontendToolDefinition> getFrontendTools() { return frontendTools; }
    public void setFrontendTools(List<AiFrontendToolDefinition> frontendTools) { this.frontendTools = frontendTools; }
}
