package com.ruoyi.ai.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.ai.dto.AiConversationCreateRequest;
import com.ruoyi.ai.service.AiConversationService;
import com.ruoyi.common.core.domain.AjaxResult;

@RestController
@RequestMapping("/ai/chat/conversations")
public class AiConversationController
{
    private final AiConversationService service;

    public AiConversationController(AiConversationService service)
    {
        this.service = service;
    }

    @PostMapping
    public AjaxResult create(@RequestBody(required = false) AiConversationCreateRequest request)
    {
        AiConversationCreateRequest value = request == null ? new AiConversationCreateRequest() : request;
        return AjaxResult.success(service.create(value.getModelId(), value.getReasoningEffort(), value.getRoute()));
    }

    @GetMapping
    public AjaxResult list(@RequestParam(required = false) String keyword)
    {
        return AjaxResult.success(service.list(keyword));
    }

    @GetMapping("/last")
    public AjaxResult last()
    {
        return AjaxResult.success(service.last());
    }

    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id)
    {
        return AjaxResult.success(service.detail(id));
    }

    @GetMapping("/{id}/run-state")
    public AjaxResult runState(@PathVariable Long id)
    {
        return AjaxResult.success(service.runState(id));
    }

    @PutMapping("/{id}/title")
    public AjaxResult rename(@PathVariable Long id, @RequestBody Map<String, String> body)
    {
        service.rename(id, body.get("title"));
        return AjaxResult.success();
    }

    @PutMapping("/{id}/archive")
    public AjaxResult archive(@PathVariable Long id)
    {
        service.archive(id);
        return AjaxResult.success();
    }

    @DeleteMapping("/{id}")
    public AjaxResult delete(@PathVariable Long id)
    {
        service.delete(id);
        return AjaxResult.success();
    }
}
