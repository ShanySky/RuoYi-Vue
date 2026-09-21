package com.ruoyi.ai.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.mapper.AiAuthorizationMapper;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.system.service.ISysUserService;

/** 每次披露和执行重新核查登录有效性及当前业务授权，不更新旧登录缓存。 */
@Service
public class AiFreshIdentity
{
    private final ISysUserService users;
    private final SysPermissionService permissions;
    private final RedisCache redis;
    private final AiAuthorizationMapper scope;
    private final ObjectMapper json;

    public AiFreshIdentity(ISysUserService users, SysPermissionService permissions, RedisCache redis,
            AiAuthorizationMapper scope, ObjectMapper json)
    {
        this.users = users;
        this.permissions = permissions;
        this.redis = redis;
        this.scope = scope;
        this.json = json;
    }

    public LoginUser refresh()
    {
        LoginUser old = SecurityUtils.getLoginUser();
        if (old.getToken() == null) throw denied();
        LoginUser active = redis.getCacheObject(CacheConstants.LOGIN_TOKEN_KEY + old.getToken());
        if (active == null || !old.getUserId().equals(active.getUserId())) throw denied();
        SysUser user = users.selectUserById(old.getUserId());
        if (user == null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())) throw denied();
        LoginUser current = new LoginUser(user.getUserId(), user.getDeptId(), user, permissions.getMenuPermission(user));
        current.setToken(old.getToken());
        current.setExpireTime(active.getExpireTime());
        current.setLoginTime(active.getLoginTime());
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(current, null,
                current.getAuthorities());
        if (previous != null) authentication.setDetails(previous.getDetails());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return current;
    }

    public String fingerprint(LoginUser user)
    {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("userId", user.getUserId());
        facts.put("deptId", user.getDeptId());
        facts.put("permissions", new TreeSet<>(user.getPermissions()));
        List<Map<String, Object>> roles = new ArrayList<>();
        if (user.getUser().getRoles() != null)
        {
            user.getUser().getRoles().stream().sorted(Comparator.comparing(SysRole::getRoleId)).forEach(role -> {
                Map<String, Object> fact = new LinkedHashMap<>();
                fact.put("id", role.getRoleId());
                fact.put("status", role.getStatus());
                fact.put("key", role.getRoleKey());
                fact.put("scope", role.getDataScope());
                fact.put("permissions", role.getPermissions() == null ? List.of() : new TreeSet<>(role.getPermissions()));
                roles.add(fact);
            });
        }
        facts.put("roles", roles);
        facts.put("roleDepartments", scope.roleDepartments(user.getUserId()));
        facts.put("departmentHierarchy", scope.departmentHierarchy());
        if (!user.getUser().isAdmin())
        {
            Long revision = scope.scopeRevision();
            if (revision == null) throw new ServiceException("数据归属授权保护不可用，暂不能读取业务结果");
            facts.put("scopeRevision", revision);
        }
        try { return AiApiCatalog.hash(json.writeValueAsString(facts)); }
        catch (Exception error) { throw new ServiceException("无法核对当前业务授权"); }
    }

    private ServiceException denied()
    {
        return new ServiceException("当前登录或业务账号已失效，请重新登录", 401);
    }
}
