package com.ruoyi.ai.workspace;

import org.springframework.stereotype.Service;
import com.ruoyi.ai.mapper.AiWorkspaceMapper;
import com.ruoyi.ai.server.AiApiCatalog;
import com.ruoyi.ai.server.AiFreshIdentity;
import com.ruoyi.common.exception.ServiceException;

@Service
public class AiWorkspaceAccess
{
    private final AiWorkspaceMapper policies;
    private final AiFreshIdentity identity;
    public AiWorkspaceAccess(AiWorkspaceMapper policies, AiFreshIdentity identity)
    {
        this.policies = policies;
        this.identity = identity;
    }

    public String authorization()
    {
        var user = identity.refresh();
        var policy = policies.policy();
        if (policy == null || !policy.enabled() || !(user.getUser().isAdmin() || user.getPermissions().contains("ai:workspace:use")))
            throw new ServiceException("当前用户没有工作空间权限或此能力已关闭");
        return AiApiCatalog.hash(identity.fingerprint(user) + ":workspace:" + policy.revision());
    }
}
