package com.ruoyi.ai.dto;

public class AiChatTurnResponse
{
    private String type;
    private Long conversationId;
    private String message;
    private ToolCall toolCall;

    public static AiChatTurnResponse message(Long conversationId, String message)
    {
        AiChatTurnResponse response = new AiChatTurnResponse();
        response.setType("MESSAGE");
        response.setConversationId(conversationId);
        response.setMessage(message);
        return response;
    }

    public static AiChatTurnResponse toolCall(Long conversationId, ToolCall call)
    {
        AiChatTurnResponse response = new AiChatTurnResponse();
        response.setType("TOOL_CALL");
        response.setConversationId(conversationId);
        response.setToolCall(call);
        return response;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public ToolCall getToolCall() { return toolCall; }
    public void setToolCall(ToolCall toolCall) { this.toolCall = toolCall; }

    public static class ToolCall
    {
        private String callId;
        private String name;
        private String arguments;
        private String riskLevel;
        private String description;

        public String getCallId() { return callId; }
        public void setCallId(String callId) { this.callId = callId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getArguments() { return arguments; }
        public void setArguments(String arguments) { this.arguments = arguments; }
        public String getRiskLevel() { return riskLevel; }
        public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
