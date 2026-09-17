package com.ruoyi.ai.dto;

import jakarta.validation.constraints.NotBlank;

public class AiToolResultRequest
{
    @NotBlank
    private String callId;
    private Boolean success;
    private Object result;
    private String error;

    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public Object getResult() { return result; }
    public void setResult(Object result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
