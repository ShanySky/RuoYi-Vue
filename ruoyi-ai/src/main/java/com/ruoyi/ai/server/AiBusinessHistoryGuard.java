package com.ruoyi.ai.server;

import org.springframework.stereotype.Service;
import com.ruoyi.ai.mapper.AiServerCallMapper;
import com.ruoyi.common.exception.ServiceException;

/** 保留原始历史，但阻止失去来源授权后的内容再次披露或进入模型上下文。 */
@Service
public class AiBusinessHistoryGuard
{
    private final AiServerCallMapper calls;
    private final AiApiAccess access;

    public AiBusinessHistoryGuard(AiServerCallMapper calls, AiApiAccess access)
    {
        this.calls = calls;
        this.access = access;
    }

    public boolean protectedHistory(Long conversationId)
    {
        return !calls.sources(conversationId).isEmpty();
    }

    public void requireReadable(Long conversationId)
    {
        for (var source : calls.sources(conversationId))
        {
            try
            {
                var capability = access.require(source.capabilityId());
                if (!source.authorizationHash().equals(access.authorization(capability))) throw new ServiceException("");
            }
            catch (ServiceException error)
            {
                throw new ServiceException("该会话引用的业务授权或数据范围已变化，历史内容暂不可读取；请新建会话重新查询");
            }
        }
    }
}
