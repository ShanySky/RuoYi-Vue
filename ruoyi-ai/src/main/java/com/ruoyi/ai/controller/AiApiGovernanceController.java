package com.ruoyi.ai.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.ai.server.AiApiAccess;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;

@RestController
@RequestMapping("/ai/admin/apis")
public class AiApiGovernanceController
{
    private final AiApiAccess access;

    public AiApiGovernanceController(AiApiAccess access) { this.access = access; }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:api:view')")
    public AjaxResult list() { return AjaxResult.success(access.governance()); }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('ai:api:edit')")
    @Log(title = "AI 后端接口治理", businessType = BusinessType.UPDATE)
    public AjaxResult configure(@PathVariable String id, @RequestBody PolicyRequest request)
    {
        access.configure(id, request.fingerprint(), request.enabled());
        return AjaxResult.success();
    }

    public record PolicyRequest(String fingerprint, boolean enabled) { }
}
