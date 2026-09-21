package com.ruoyi.ai.controller;

import java.util.Set;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.ai.data.AiDataAccess;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;

@RestController
@RequestMapping("/ai/admin/data")
public class AiDataGovernanceController
{
    private final AiDataAccess access;
    public AiDataGovernanceController(AiDataAccess access) { this.access = access; }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('ai:data:view')")
    public AjaxResult list() { return AjaxResult.success(access.governance()); }

    @PutMapping("/{key}")
    @PreAuthorize("@ss.hasPermi('ai:data:edit')")
    @Log(title = "AI 数据查询治理", businessType = BusinessType.UPDATE)
    public AjaxResult configure(@PathVariable String key, @RequestBody PolicyRequest request)
    {
        access.configure(key, request.fingerprint(), request.enabled(), request.fields(), request.operations());
        return AjaxResult.success();
    }

    public record PolicyRequest(String fingerprint, boolean enabled, Set<String> fields, Set<String> operations) { }
}
