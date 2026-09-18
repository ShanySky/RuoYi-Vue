package com.ruoyi.ai.dto;
public class AiChatTurnResponse {
 private String type,runStatus,message; private Long conversationId,runId; private ToolCall toolCall;
 public static AiChatTurnResponse message(Long conversationId,Long runId,String message){AiChatTurnResponse r=base("MESSAGE",conversationId,runId);r.setMessage(message);r.setRunStatus("COMPLETED");return r;}
 public static AiChatTurnResponse toolCall(Long conversationId,Long runId,ToolCall call){AiChatTurnResponse r=base("TOOL_CALL",conversationId,runId);r.setToolCall(call);r.setRunStatus("WAITING_TOOL");return r;}
 public static AiChatTurnResponse state(Long conversationId,Long runId,String status){AiChatTurnResponse r=base("RUN_STATE",conversationId,runId);r.setRunStatus(status);return r;}
 private static AiChatTurnResponse base(String type,Long cid,Long rid){AiChatTurnResponse r=new AiChatTurnResponse();r.type=type;r.conversationId=cid;r.runId=rid;return r;}
 public String getType(){return type;} public void setType(String v){type=v;} public Long getConversationId(){return conversationId;} public void setConversationId(Long v){conversationId=v;}
 public Long getRunId(){return runId;} public void setRunId(Long v){runId=v;} public String getRunStatus(){return runStatus;} public void setRunStatus(String v){runStatus=v;}
 public String getMessage(){return message;} public void setMessage(String v){message=v;} public ToolCall getToolCall(){return toolCall;} public void setToolCall(ToolCall v){toolCall=v;}
 public static class ToolCall {
  private String callId,name,arguments,riskLevel,description; public String getCallId(){return callId;} public void setCallId(String v){callId=v;} public String getName(){return name;} public void setName(String v){name=v;}
  public String getArguments(){return arguments;} public void setArguments(String v){arguments=v;} public String getRiskLevel(){return riskLevel;} public void setRiskLevel(String v){riskLevel=v;} public String getDescription(){return description;} public void setDescription(String v){description=v;}
 }
}
