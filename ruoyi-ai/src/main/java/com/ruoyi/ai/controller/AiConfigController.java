package com.ruoyi.ai.controller;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.ai.domain.AiModel;
import com.ruoyi.ai.dto.AiModelSelectionRequest;
import com.ruoyi.ai.dto.AiModelRuntimeSettingsRequest;
import com.ruoyi.ai.dto.AiModelStatusRequest;
import com.ruoyi.ai.dto.AiProviderSaveRequest;
import com.ruoyi.ai.dto.AiReasoningEffortRequest;
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
    @Log(title = "AI 服务配置", businessType = BusinessType.UPDATE, excludeParamNames = { "token" })
    @PostMapping("/provider")
    public AjaxResult saveProvider(@Validated @RequestBody AiProviderSaveRequest request)
    {
        return AjaxResult.success(configService.saveProvider(request));
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PostMapping("/provider/test-model-load")
    public AjaxResult testModelLoad(@RequestBody AiProviderSaveRequest request)
    {
        return AjaxResult.success(configService.testModelLoad(request));
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PostMapping("/models/discover")
    public AjaxResult discoverModels()
    {
        return AjaxResult.success(configService.discoverModels());
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @Log(title = "AI 模型添加", businessType = BusinessType.INSERT)
    @PostMapping("/models")
    public AjaxResult addModels(@Validated @RequestBody AiModelSelectionRequest request)
    {
        return AjaxResult.success(configService.addModels(request.getModelCodes()));
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @Log(title = "AI 模型移除", businessType = BusinessType.DELETE)
    @DeleteMapping("/models/{modelId}")
    public AjaxResult removeModel(@PathVariable Long modelId)
    {
        configService.removeModel(modelId);
        return AjaxResult.success();
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
    @Log(title = "AI 模型启停", businessType = BusinessType.UPDATE)
    @PutMapping("/models/{modelId}/enabled")
    public AjaxResult setEnabled(@PathVariable Long modelId, @Validated @RequestBody AiModelStatusRequest request)
    {
        configService.setModelEnabled(modelId, Boolean.TRUE.equals(request.getEnabled()));
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @Log(title = "AI 系统默认模型", businessType = BusinessType.UPDATE)
    @PutMapping("/models/{modelId}/default")
    public AjaxResult setDefault(@PathVariable Long modelId)
    {
        configService.setDefaultModel(modelId);
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @Log(title = "AI 模型默认思考档位", businessType = BusinessType.UPDATE)
    @PutMapping("/models/{modelId}/default-reasoning")
    public AjaxResult setDefaultReasoning(@PathVariable Long modelId, @RequestBody AiReasoningEffortRequest request)
    {
        configService.setDefaultReasoningEffort(modelId, request == null ? null : request.getReasoningEffort());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @Log(title = "AI 模型运行参数", businessType = BusinessType.UPDATE)
    @PutMapping("/models/{modelId}/runtime-settings")
    public AjaxResult setRuntimeSettings(@PathVariable Long modelId, @RequestBody AiModelRuntimeSettingsRequest request)
    {
        configService.setModelRuntimeSettings(modelId, request == null ? null : request.getContextWindowTokens(),
                request == null ? null : request.getAutoCompaction(),
                request == null ? null : request.getCompactionThresholdPercent());
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @Log(title = "AI 模型能力检测", businessType = BusinessType.UPDATE)
    @PostMapping("/models/{modelId}/detect-capabilities")
    public AjaxResult detectCapabilities(@PathVariable Long modelId)
    {
        configService.requireSystemModel(modelId);
        return AjaxResult.success(capabilityService.detectCapabilities(modelId));
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PostMapping("/models/{modelId}/test-reasoning")
    public AjaxResult testReasoning(@PathVariable Long modelId)
    {
        configService.requireSystemModel(modelId);
        return AjaxResult.success("操作成功", capabilityService.testReasoning(modelId));
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PostMapping("/models/{modelId}/test-chat")
    public AjaxResult testChat(@PathVariable Long modelId)
    {
        return AjaxResult.success("操作成功", configService.testChat(modelId));
    }

    @PreAuthorize("@ss.hasPermi('ai:config:edit')")
    @PostMapping("/models/{modelId}/test-tools")
    public AjaxResult testTools(@PathVariable Long modelId)
    {
        configService.requireSystemModel(modelId);
        return AjaxResult.success("操作成功", capabilityService.testToolCalling(modelId));
    }
}
