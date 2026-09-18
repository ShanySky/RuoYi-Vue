package com.ruoyi.ai.controller;
import java.util.Map; import org.springframework.web.bind.annotation.*; import com.ruoyi.ai.service.AiRunService; import com.ruoyi.common.core.domain.AjaxResult; import com.ruoyi.common.utils.SecurityUtils;
@RestController @RequestMapping("/ai/chat/runs")
public class AiRunController {
 private final AiRunService service; public AiRunController(AiRunService s){service=s;}
 @GetMapping("/{runId}") public AjaxResult get(@PathVariable Long runId){return AjaxResult.success(service.requireOwned(runId,SecurityUtils.getUserId()));}
 @PostMapping("/{runId}/cancel") public AjaxResult cancel(@PathVariable Long runId,@RequestBody(required=false)Map<String,String> body){String reason=body==null?"USER_STOP":body.getOrDefault("reason","USER_STOP");return AjaxResult.success(service.cancel(runId,SecurityUtils.getUserId(),reason));}
 @PostMapping("/client/{clientRunKey}/cancel") public AjaxResult cancelByClientKey(@PathVariable String clientRunKey,@RequestBody(required=false)Map<String,String> body){String reason=body==null?"USER_STOP":body.getOrDefault("reason","USER_STOP");return AjaxResult.success(service.cancelByClientKey(clientRunKey,SecurityUtils.getUserId(),reason));}
}