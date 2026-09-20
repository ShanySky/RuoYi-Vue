package com.ruoyi.ai.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.ai.server.AiServerToolService;
import com.ruoyi.common.core.domain.AjaxResult;

@RestController
@RequestMapping("/ai/chat/conversations/{conversationId}/server-tools")
public class AiServerToolController
{
    private final AiServerToolService tools;

    public AiServerToolController(AiServerToolService tools) { this.tools = tools; }

    @PostMapping("/{callId}/confirm")
    public AjaxResult confirm(@PathVariable Long conversationId, @PathVariable String callId,
            @RequestBody Confirmation confirmation)
    {
        tools.execute(conversationId, callId, confirmation.approved());
        return AjaxResult.success(tools.outcome(callId));
    }

    public record Confirmation(boolean approved) { }
}
