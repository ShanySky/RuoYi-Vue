package com.ruoyi.ai.server;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.server.AiApiCatalog.Capability;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.ServletUtils;

/** 只向本实例真实端口发送契约确定的请求，完整复用原业务 HTTP 链。 */
@Service
public class AiNativeApiInvoker
{
    public static final int MAX_RESULT_BYTES = 256 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final AiNativeRequestGate gate;
    private final ObjectMapper json;
    private final ApiContractSchema schemas;
    private final String tokenHeader;
    private final java.util.concurrent.Semaphore concurrency = new java.util.concurrent.Semaphore(16);

    public AiNativeApiInvoker(AiNativeRequestGate gate, ObjectMapper json, ApiContractSchema schemas,
            @Value("${token.header:Authorization}") String tokenHeader)
    {
        this.gate = gate;
        this.json = json;
        this.schemas = schemas;
        this.tokenHeader = tokenHeader;
    }

    public JsonNode invoke(String callId, Capability capability, JsonNode arguments) throws Exception
    {
        if (!concurrency.tryAcquire()) throw new ServiceException("服务端业务调用并发已达上限");
        try { return invokeWithinLimit(callId, capability, arguments); }
        finally { concurrency.release(); }
    }

    private JsonNode invokeWithinLimit(String callId, Capability capability, JsonNode arguments) throws Exception
    {
        schemas.validate(capability.inputSchema(), arguments);
        HttpServletRequest caller = ServletUtils.getRequest();
        String token = caller.getHeader(tokenHeader);
        if (token == null || token.isBlank()) throw new ServiceException("当前登录令牌不可用于业务调用");
        String path = capability.path();
        for (var values = arguments.path("path").fields(); values.hasNext();)
        {
            var value = values.next();
            String segment = scalar(value.getValue());
            if (!segment.matches("[A-Za-z0-9_.,-]{1,512}") || segment.contains(".."))
                throw new ServiceException("路径参数包含不支持的字符");
            path = path.replace("{" + value.getKey() + "}", segment);
        }
        if (path.contains("{") || path.contains("}")) throw new ServiceException("缺少业务路径参数");
        List<String> query = new ArrayList<>();
        for (var values = arguments.path("query").fields(); values.hasNext();)
        {
            var value = values.next();
            query.add(encode(value.getKey()) + "=" + encode(scalar(value.getValue())));
        }
        if (capability.pageable())
        {
            if (!arguments.path("query").has("pageNum")) query.add("pageNum=1");
            if (!arguments.path("query").has("pageSize")) query.add("pageSize=20");
        }
        String fullPath = caller.getContextPath() + path;
        URI uri = URI.create("http://127.0.0.1:" + caller.getLocalPort() + fullPath
                + (query.isEmpty() ? "" : "?" + String.join("&", query)));
        String nonce = gate.issue(callId, capability.method(), fullPath);
        CompletableFuture<HttpResponse<byte[]>> future = null;
        try
        {
            HttpRequest.BodyPublisher body = arguments.has("body")
                    ? HttpRequest.BodyPublishers.ofString(json.writeValueAsString(arguments.get("body")), StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody();
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                    .header(tokenHeader, token).header(AiNativeRequestGate.HEADER, nonce)
                    .header("Content-Type", "application/json").method(capability.method(), body).build();
            future = client.sendAsync(request, info -> new BoundedBody());
            HttpResponse<byte[]> response = future.get(35, TimeUnit.SECONDS);
            if (response.statusCode() != 200)
                throw new ServiceException("原业务入口拒绝了请求（状态 " + response.statusCode() + "）");
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.contains("json")) throw new ServiceException("原接口未返回支持的 JSON 结果");
            return json.readTree(response.body());
        }
        catch (Exception error)
        {
            if (future != null) throw new UncertainExecutionException(error);
            throw error;
        }
        finally
        {
            gate.revoke(nonce);
            if (future != null && !future.isDone()) future.cancel(true);
        }
    }

    private String scalar(JsonNode value)
    {
        if (value.isArray())
        {
            List<String> values = new ArrayList<>();
            value.forEach(item -> values.add(item.asText()));
            return String.join(",", values);
        }
        return value.asText();
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    /** 请求发出后无法取得完整响应，不能推断业务事务没有提交。 */
    public static class UncertainExecutionException extends Exception
    {
        public UncertainExecutionException(Throwable cause) { super("原业务请求已发出但结果无法确认", cause); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]>
    {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription)
        {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers)
        {
            for (ByteBuffer buffer : buffers)
            {
                if ((long) bytes.size() + buffer.remaining() > MAX_RESULT_BYTES)
                {
                    subscription.cancel();
                    body.completeExceptionally(new ServiceException("接口结果超过上限，请缩小查询范围"));
                    return;
                }
                byte[] value = new byte[buffer.remaining()];
                buffer.get(value);
                bytes.writeBytes(value);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
