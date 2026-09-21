package com.ruoyi.ai.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.WriteListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.ai.mapper.AiWorkspaceMapper;
import com.ruoyi.ai.workspace.AiWorkspaceClient;
import com.ruoyi.ai.workspace.AiWorkspaceService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

@RestController
public class AiWorkspaceController
{
    private final AiWorkspaceMapper policies;
    private final AiWorkspaceClient client;
    private final AiWorkspaceService workspace;
    private final Semaphore downloads = new Semaphore(4);
    public AiWorkspaceController(AiWorkspaceMapper policies, AiWorkspaceClient client, AiWorkspaceService workspace)
    { this.policies = policies; this.client = client; this.workspace = workspace; }

    @GetMapping("/ai/admin/workspace")
    @PreAuthorize("@ss.hasPermi('ai:workspace:view')")
    public AjaxResult governance()
    {
        var policy = policies.policy();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("enabled", policy.enabled());
        view.put("revision", policy.revision());
        try { view.put("environment", client.status()); }
        catch (ServiceException error) { view.put("environment", Map.of("ready", false, "message", error.getMessage())); }
        return AjaxResult.success(view);
    }

    @PutMapping("/ai/admin/workspace")
    @PreAuthorize("@ss.hasPermi('ai:workspace:edit')")
    @Log(title = "AI 工作空间治理", businessType = BusinessType.UPDATE)
    public AjaxResult configure(@RequestBody PolicyRequest request)
    {
        if (request.enabled() && !client.ready()) throw new ServiceException("隔离服务与资源保护未就绪，不能开放工作空间");
        if (policies.configure(request.enabled(), request.revision(), SecurityUtils.getUsername()) != 1)
            throw new ServiceException("治理配置已变化，请刷新后核对");
        return AjaxResult.success();
    }
    public record PolicyRequest(boolean enabled, long revision) { }

    @GetMapping("/ai/chat/conversations/{id}/artifacts")
    public AjaxResult artifacts(@PathVariable Long id) { return AjaxResult.success(workspace.list(id)); }

    @GetMapping("/ai/artifacts/{id}/download")
    @Log(title = "请求 AI 成果下载", businessType = BusinessType.EXPORT)
    public void download(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) throws java.io.IOException
    {
        var artifact = workspace.requireArtifact(id);
        if (!downloads.tryAcquire()) throw new ServiceException("成果下载并发已达上限，请稍后重试");
        var released = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) downloads.release(); };
        try
        {
            byte[] data = workspace.read(artifact);
            response.setContentType("application/octet-stream");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(artifact.name(), StandardCharsets.UTF_8).replace("+", "%20"));
            response.setContentLengthLong(data.length);
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            var async = request.startAsync();
            async.setTimeout(60000);
            async.addListener(new AsyncListener() {
                public void onComplete(AsyncEvent event) { release.run(); }
                public void onTimeout(AsyncEvent event) { release.run(); async.complete(); }
                public void onError(AsyncEvent event) { release.run(); async.complete(); }
                public void onStartAsync(AsyncEvent event) { }
            });
            var output = response.getOutputStream();
            output.setWriteListener(new WriteListener() {
                private int offset;
                public void onWritePossible() throws java.io.IOException
                {
                    var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
                    try
                    {
                        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(authentication);
                        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
                        while (!released.get() && output.isReady() && offset < data.length)
                        {
                            workspace.requireConversation(artifact.conversationId());
                            int size = Math.min(65536, data.length - offset);
                            output.write(data, offset, size);
                            offset += size;
                        }
                        if (offset == data.length) async.complete();
                    }
                    catch (Exception error) { release.run(); async.complete(); }
                    finally { org.springframework.security.core.context.SecurityContextHolder.setContext(previous); }
                }
                public void onError(Throwable error) { release.run(); async.complete(); }
            });
        }
        catch (Exception error) { release.run(); throw error; }
    }
}
