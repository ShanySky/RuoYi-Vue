package com.ruoyi.ai.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.ai.dto.AiChatTurnRequest;
import com.ruoyi.ai.service.AiAgentLoopService;
import com.ruoyi.common.core.domain.AjaxResult;

@RestController
@RequestMapping("/ai/chat")
public class AiChatController
{
    private final AiAgentLoopService agentLoopService;

    public AiChatController(AiAgentLoopService agentLoopService)
    {
        this.agentLoopService = agentLoopService;
    }

    @PostMapping("/turn")
    public AjaxResult turn(@RequestBody AiChatTurnRequest request)
    {
        return AjaxResult.success(agentLoopService.turn(request));
    }
}
