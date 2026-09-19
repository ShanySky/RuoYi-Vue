package com.ruoyi.ai.protocol;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import com.ruoyi.ai.domain.AiPendingToolCall;
import com.ruoyi.ai.dto.AiChatTurnRequest;
import com.ruoyi.ai.dto.AiFrontendToolDefinition;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

@Component
public class AiCapabilityProtocolValidator
{
    public static final String PROTOCOL_V1 = "ruoyi-semantic-page-v1";

    public void validateRequest(AiChatTurnRequest request)
    {
        if (request == null || !isSemanticPageRequest(request))
        {
            return;
        }
        if (!PROTOCOL_V1.equals(request.getCapabilityProtocol()))
        {
            throw new ServiceException("页面能力协议无效，仅支持 " + PROTOCOL_V1);
        }
        requireText(request.getPageId(), "pageId", 128);
        requireText(request.getRoute(), "route", 255);
        requireText(request.getPageInstanceId(), "pageInstanceId", 64);
        if (!request.getRoute().startsWith("/"))
        {
            throw new ServiceException("页面能力运行时无效：route 必须为绝对路径");
        }
        if (request.getPageVersion() == null || request.getPageVersion() <= 0)
        {
            throw new ServiceException("页面能力运行时不完整：pageVersion 必须为正整数");
        }
    }

    public boolean isSemanticPageRequest(AiChatTurnRequest request)
    {
        if (request == null)
        {
            return false;
        }
        if (StringUtils.isNotBlank(request.getPageId())
                || StringUtils.isNotBlank(request.getPageInstanceId())
                || request.getPageVersion() != null)
        {
            return true;
        }
        List<AiFrontendToolDefinition> tools = request.getFrontendTools();
        return tools != null && tools.stream()
                .filter(Objects::nonNull)
                .map(AiFrontendToolDefinition::getName)
                .anyMatch(name -> StringUtils.isNotBlank(name) && !"app_navigate".equals(name));
    }

    public void validateContinuation(AiChatTurnRequest request, AiPendingToolCall pending, boolean requireSamePage)
    {
        validateRequest(request);
        if (!requireSamePage)
        {
            return;
        }
        if (pending == null || !PROTOCOL_V1.equals(pending.getCapabilityProtocol())
                || StringUtils.isBlank(pending.getPageId())
                || StringUtils.isBlank(pending.getRoute())
                || StringUtils.isBlank(pending.getPageInstanceId())
                || pending.getPageVersion() == null)
        {
            throw new ServiceException("原页面能力快照无效，旧页面工具结果已失效");
        }
        if (!Objects.equals(pending.getCapabilityProtocol(), request.getCapabilityProtocol())
                || !Objects.equals(pending.getPageId(), request.getPageId())
                || !Objects.equals(pending.getRoute(), request.getRoute())
                || !Objects.equals(pending.getPageInstanceId(), request.getPageInstanceId())
                || !Objects.equals(pending.getPageVersion(), request.getPageVersion()))
        {
            throw new ServiceException("页面能力实例已经变化，旧页面工具结果已失效");
        }
    }

    private void requireText(String value, String field, int maxLength)
    {
        if (StringUtils.isBlank(value))
        {
            throw new ServiceException("页面能力运行时不完整：缺少 " + field);
        }
        if (value.length() > maxLength)
        {
            throw new ServiceException("页面能力运行时无效：" + field + " 长度超过 " + maxLength);
        }
    }
}
