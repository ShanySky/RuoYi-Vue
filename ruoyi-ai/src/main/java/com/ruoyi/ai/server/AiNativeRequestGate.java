package com.ruoyi.ai.server;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import com.ruoyi.ai.domain.AiRun;
import com.ruoyi.ai.mapper.AiRunMapper;
import com.ruoyi.ai.mapper.AiServerCallMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

/** 一次性回环凭证只允许服务端已认领调用进入原业务接口。 */
@Service
public class AiNativeRequestGate
{
    public static final String HEADER = "X-RuoYi-Ai-Invocation";
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final AiServerCallMapper calls;
    private final AiRunMapper runs;
    private final AiApiAccess access;

    public AiNativeRequestGate(AiServerCallMapper calls, AiRunMapper runs, AiApiAccess access)
    {
        this.calls = calls;
        this.runs = runs;
        this.access = access;
    }

    public synchronized String issue(String callId, String method, String path)
    {
        tickets.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
        if (tickets.size() >= 16) throw new ServiceException("服务端业务调用并发已达上限");
        String nonce = UUID.randomUUID().toString();
        tickets.put(nonce, new Ticket(callId, SecurityUtils.getUserId(), method, path, Instant.now().plusSeconds(40)));
        return nonce;
    }

    public void consume(HttpServletRequest request)
    {
        Ticket ticket = tickets.remove(request.getHeader(HEADER));
        if (ticket == null || ticket.expires().isBefore(Instant.now())
                || !ticket.userId().equals(SecurityUtils.getUserId())
                || !ticket.method().equals(request.getMethod()) || !ticket.path().equals(request.getRequestURI())
                || !("127.0.0.1".equals(request.getRemoteAddr()) || "0:0:0:0:0:0:0:1".equals(request.getRemoteAddr())))
            throw new ServiceException("服务端调用凭证无效");
        var call = calls.get(ticket.callId());
        AiRun run = call == null ? null : runs.selectById(call.runId());
        if (call == null || !"EXECUTING".equals(call.status()) || run == null
                || !"WAITING_TOOL".equals(run.getStatus()) || !ticket.userId().equals(run.getUserId()))
            throw new ServiceException("运行已停止或调用状态已变化");
        var capability = access.require(call.capabilityId());
        if (!call.authorizationHash().equals(access.authorization(capability)))
            throw new ServiceException("执行前业务授权已变化");
    }

    public void revoke(String nonce) { tickets.remove(nonce); }

    private record Ticket(String callId, Long userId, String method, String path, Instant expires) { }
}
