package com.ruoyi.ai.server;

import org.springframework.stereotype.Service;
import com.ruoyi.ai.data.AiDataAccess;
import com.ruoyi.ai.workspace.AiWorkspaceAccess;
import com.ruoyi.ai.workspace.AiWorkspaceTools;
import com.ruoyi.common.exception.ServiceException;

/** 真实业务与工作空间来源共用结果、成果和历史保护。 */
@Service
public class AiBusinessAccess
{
    private final AiApiAccess apis;
    private final AiDataAccess data;
    private final AiWorkspaceAccess workspace;

    public AiBusinessAccess(AiApiAccess apis, AiDataAccess data, AiWorkspaceAccess workspace)
    { this.apis = apis; this.data = data; this.workspace = workspace; }

    public String authorization(String id)
    {
        if (id != null && id.startsWith("api_")) return apis.authorization(apis.require(id));
        if (id != null && id.startsWith("data_")) return data.require(id).authorization();
        if (AiWorkspaceTools.supports(id)) return workspace.authorization();
        throw new ServiceException("业务来源不存在或无法可靠授权");
    }
}
