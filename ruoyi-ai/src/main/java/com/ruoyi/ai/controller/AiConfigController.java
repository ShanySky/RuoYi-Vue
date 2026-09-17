package com.ruoyi.ai.controller;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.ai.domain.AiModel;
import com.ruoyi.ai.dto.AiModelStatusRequest;
import com.ruoyi.ai.dto.AiProviderSaveRequest;
import com.ruoyi.ai.service.AiConfigService;
import com.ruoyi.ai.service.AiModelCapabilityService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;

@RestController
@RequestMapping("/ai/config")
public class AiConfigController
{
    private final AiConfigService configService;
    private final AiModelCapabilityService capabilityService;

    public AiConfigController(AiConfigService configService, AiModelCapabilityService capabilityService)
    {
        this.configService = configService;
        this.capabilityService = capabilityService;
    }

    @PreAuthorize("@ss.hasPermi('ai:config:view')")
    @GetMapping("/provider")
    public AjaxResult getProvider()
    {
        return AjaxResult.success(configService.getProviderView());
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @Log(title = "AI 服务配置", businessType = BusinessType.UPDATE)
    @PostMapping("/provider")
    public AjaxResult saveProvider(@Validated @RequestBody AiProviderSaveRequest request)
    {
        return AjaxResult.success(configService.saveProvider(request));
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PostMapping("/provider/test")
    public AjaxResult testProvider(@RequestBody AiProviderSaveRequest request)
    {
        return AjaxResult.success(configService.testConnection(request));
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @Log(title = "AI 模型同步", businessType = BusinessType.UPDATE)
    @PostMapping("/models/sync")
    public AjaxResult syncModels()
    {
        return AjaxResult.success(configService.syncModels());
    }

    @PreAuthorize("@ss.hasPermi('ai:config:view')")
    @GetMapping("/models")
    public AjaxResult listModels()
    {
        return AjaxResult.success(configService.listModels());
    }

    @GetMapping("/models/enabled")
    public AjaxResult listEnabledModels()
    {
        List<AiModel> models = configService.listEnabledModels();
        return AjaxResult.success(models);
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PutMapping("/models/{modelId}/enabled")
    public AjaxResult setEnabled(@PathVariable Long modelId, @Validated @RequestBody AiModelStatusRequest request)
    {
        configService.setModelEnabled(modelId, Boolean.TRUE.equals(request.getEnabled()));
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PutMapping("/models/{modelId}/default")
    public AjaxResult setDefault(@PathVariable Long modelId)
    {
        configService.setDefaultModel(modelId);
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PostMapping("/models/{modelId}/test-chat")
    public AjaxResult testChat(@PathVariable Long modelId)
    {
        return AjaxResult.success(configService.testChat(modelId));
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PostMapping("/models/{modelId}/test-tools")
    public AjaxResult testTools(@PathVariable Long modelId)
    {
        return AjaxResult.success(capabilityService.testToolCalling(modelId));
    }
}
