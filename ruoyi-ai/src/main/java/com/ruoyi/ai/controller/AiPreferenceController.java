package com.ruoyi.ai.controller;
import org.springframework.web.bind.annotation.*; import com.ruoyi.ai.dto.AiPreferenceRequest; import com.ruoyi.ai.service.AiPreferenceService; import com.ruoyi.common.core.domain.AjaxResult;
@RestController @RequestMapping("/ai/preferences")
public class AiPreferenceController {
 private final AiPreferenceService service; public AiPreferenceController(AiPreferenceService s){service=s;}
 @GetMapping public AjaxResult get(){return AjaxResult.success(service.view());}
 @PutMapping public AjaxResult save(@RequestBody AiPreferenceRequest r){return AjaxResult.success(service.save(r));}
}