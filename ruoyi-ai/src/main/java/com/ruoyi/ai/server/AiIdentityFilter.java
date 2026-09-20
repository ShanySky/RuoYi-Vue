package com.ruoyi.ai.server;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;

/** 位于 Spring Security 登录过滤链之后、业务控制器之前，不改变普通业务请求。 */
@Component
@Order(-90)
public class AiIdentityFilter extends OncePerRequestFilter
{
    private final AiFreshIdentity identity;
    private final AiNativeRequestGate gate;
    private final ObjectMapper json;

    public AiIdentityFilter(AiFreshIdentity identity, AiNativeRequestGate gate, ObjectMapper json)
    {
        this.identity = identity;
        this.gate = gate;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request)
    {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/ai/") && !path.equals("/ai") && request.getHeader(AiNativeRequestGate.HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException
    {
        try
        {
            identity.refresh();
            if (request.getHeader(AiNativeRequestGate.HEADER) != null) gate.consume(request);
        }
        catch (ServiceException error)
        {
            response.setStatus(error.getCode() != null && error.getCode() == 401 ? 401 : 403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(json.writeValueAsString(AjaxResult.error(response.getStatus(), error.getMessage())));
            return;
        }
        chain.doFilter(request, response);
    }
}
