package com.ruoyi.ai.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.exception.ServiceException;

/** 运行时真实接口目录。只保存派生事实，不维护业务接口注册清单。 */
@Component
public class AiApiCatalog
{
    private static final Pattern PERMISSION = Pattern.compile("@ss\\.(hasPermi|hasAnyPermi)\\('([A-Za-z0-9_:,]+)'\\)");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9_/{},-]+");
    private final RequestMappingHandlerMapping mappings;
    private final ApiContractSchema schemas;
    private final JsonMapper canonical = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

    public AiApiCatalog(@Lazy @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings,
            ApiContractSchema schemas)
    {
        this.mappings = mappings;
        this.schemas = schemas;
    }

    public List<Capability> all()
    {
        List<Capability> result = new ArrayList<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.ruoyi.")) return;
            for (String path : mapping.getPatternValues())
            {
                for (RequestMethod method : mapping.getMethodsCondition().getMethods())
                {
                    result.add(describe(mapping, handler, path, method.name()));
                }
            }
        });
        result.sort(Comparator.comparing(Capability::path).thenComparing(Capability::method));
        return result;
    }

    public Capability require(String id)
    {
        return all().stream().filter(capability -> capability.id().equals(id)).findFirst()
                .orElseThrow(() -> new ServiceException("接口不存在或当前不可用"));
    }

    private Capability describe(RequestMappingInfo mapping, HandlerMethod handler, String path, String method)
    {
        String id = "api_" + hash(method + " " + path).substring(0, 24);
        PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
        if (guard == null) guard = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
        Matcher permission = PERMISSION.matcher(guard == null ? "" : guard.value());
        String permissionText = permission.matches() ? permission.group(2) : "";
        String[] parts = permissionText.split(":");
        String module = parts.length >= 2 ? parts[0] : "其他";
        String object = parts.length >= 2 ? parts[1] : handler.getBeanType().getSimpleName();
        String action = parts.length >= 3 ? parts[2] : handler.getMethod().getName();
        Log log = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Log.class);
        String title = (log == null ? object : log.title()) + " / " + action;
        String risk = "GET".equals(method) && log == null ? "READ" : "WRITE";
        String reason = "";
        Map<String, Object> schema = ApiContractSchema.object();
        if (path.startsWith("/ai/") || path.equals("/ai")) reason = "AI 治理与运行入口不向业务工具开放";
        else if (path.startsWith("/monitor/") || path.startsWith("/tool/"))
            reason = "运维、凭据缓存与代码执行入口需要独立治理适配";
        else if (permissionText.isBlank()) reason = "缺少可识别的标准业务权限";
        else if ("hasPermi".equals(permission.group(1)) && permissionText.contains(","))
            reason = "单权限声明包含多个值，不能猜测其授权含义";
        else if (!PATH.matcher(path).matches() || path.contains("..")) reason = "路径不是明确的标准业务路由";
        else if (mapping.getMethodsCondition().getMethods().size() != 1
                || !mapping.getParamsCondition().isEmpty() || !mapping.getHeadersCondition().isEmpty())
            reason = "存在多方法或特殊请求条件";
        else if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) reason = "不支持此请求方法";
        else if (handler.getMethod().getReturnType() == void.class) reason = "流式或无内容返回需专门适配";
        else
        {
            try { schema = schemas.describe(handler); }
            catch (IllegalArgumentException error) { reason = error.getMessage(); }
        }
        String fingerprint;
        try
        {
            fingerprint = hash(canonical.writeValueAsString(List.of(method, path, permissionText,
                    handler.getMethod().toGenericString(), risk, schema, reason)));
        }
        catch (Exception error)
        {
            throw new IllegalStateException("接口契约指纹生成失败", error);
        }
        return new Capability(id, module, object, action, title, method, path, permissionText, risk, schema,
                fingerprint, reason, TableDataInfo.class.isAssignableFrom(handler.getMethod().getReturnType()));
    }

    public static String hash(String value)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException error)
        {
            throw new IllegalStateException(error);
        }
    }

    public record Capability(String id, String module, String object, String action, String title,
            String method, String path, String permission, String riskLevel, Map<String, Object> inputSchema,
            String fingerprint, String unsupportedReason, boolean pageable)
    {
        public boolean supported() { return unsupportedReason.isEmpty(); }

        public boolean granted(LoginUser user)
        {
            if (!supported() || user == null || user.getPermissions() == null) return false;
            // 与原 @ss.hasPermi 一致：精确权限或总管理员权限，不引入通配授权语义。
            return user.getPermissions().contains("*:*:*") || java.util.Arrays.stream(permission.split(","))
                    .anyMatch(user.getPermissions()::contains);
        }
    }
}
