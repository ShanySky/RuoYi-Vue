package com.ruoyi.ai.server;

import org.springframework.stereotype.Service;
import com.ruoyi.ai.data.AiDataAccess;
import com.ruoyi.common.exception.ServiceException;

/** 接口与数据两种真实业务来源共用结果和历史保护。 */
@Service
public class AiBusinessAccess
{
    private final AiApiAccess apis;
    private final AiDataAccess data;

    public AiBusinessAccess(AiApiAccess apis, AiDataAccess data) { this.apis = apis; this.data = data; }

    public String authorization(String id)
    {
        if (id != null && id.startsWith("api_")) return apis.authorization(apis.require(id));
        if (id != null && id.startsWith("data_")) return data.require(id).authorization();
        throw new ServiceException("业务来源不存在或无法可靠授权");
    }
}
