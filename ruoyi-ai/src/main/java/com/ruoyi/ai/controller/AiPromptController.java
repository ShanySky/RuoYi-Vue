package com.ruoyi.ai.controller;
import org.springframework.security.access.prepost.PreAuthorize; import org.springframework.web.bind.annotation.*; import com.ruoyi.ai.dto.AiPromptUpdateRequest; import com.ruoyi.ai.service.AiPromptService; import com.ruoyi.common.annotation.Log; import com.ruoyi.common.core.domain.AjaxResult; import com.ruoyi.common.enums.BusinessType;
@RestController @RequestMapping("/ai/config/prompts")
public class AiPromptController {
 private final AiPromptService service; public AiPromptController(AiPromptService s){service=s;}
 @PreAuthorize("@ss.hasPermi('ai:prompt:view') or @ss.hasPermi('ai:config:view')") @GetMapping public AjaxResult list(){return AjaxResult.success(service.list());}
 @PreAuthorize("@ss.hasPermi('ai:prompt:edit') or @ss.hasPermi('ai:config:edit')") @Log(title="AI Prompt 配置",businessType=BusinessType.UPDATE) @PutMapping("/{type}") public AjaxResult update(@PathVariable String type,@RequestBody AiPromptUpdateRequest r){return AjaxResult.success(service.update(type,r==null?null:r.getContent(),r==null?null:r.getEnabled()));}
 @PreAuthorize("@ss.hasPermi('ai:prompt:edit') or @ss.hasPermi('ai:config:edit')") @Log(title="AI Prompt 恢复默认",businessType=BusinessType.UPDATE) @PostMapping("/{type}/restore-default") public AjaxResult restore(@PathVariable String type){return AjaxResult.success(service.restore(type));}
}
