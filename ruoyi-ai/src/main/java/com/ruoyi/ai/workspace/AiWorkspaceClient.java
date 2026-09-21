package com.ruoyi.ai.workspace;

import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;

/** 仅后端持有管理授权；调用地址来自部署配置，模型不能指定。 */
@Service
public class AiWorkspaceClient
{
    private final ObjectMapper json;
    private final URI base;
    private final String tokenFile;

    public AiWorkspaceClient(ObjectMapper json, @Value("${ruoyi.ai.workspace.url:http://127.0.0.1:28097}") String url,
            @Value("${ruoyi.ai.workspace.token-file:}") String tokenFile)
    {
        this.json = json;
        this.base = URI.create(url);
        this.tokenFile = tokenFile;
        if (base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("https".equals(base.getScheme()) || ("http".equals(base.getScheme())
                    && java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost()))))
            throw new IllegalArgumentException("工作空间服务必须使用本机回环或受保护的 HTTPS 地址");
    }

    public JsonNode call(String action, Object body)
    {
        try { return json.readTree(request(action, body, 256 * 1024)); }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw unavailable(); }
    }

    public boolean ready()
    {
        try { return call("status", Map.of()).path("ready").asBoolean(); }
        catch (Exception error) { return false; }
    }

    public Map<String, Object> status()
    {
        // MVC 使用的序列化版本与内部树模型不同，跨边界只交付普通结构。
        return json.convertValue(call("status", Map.of()), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
    }

    public byte[] read(Object body) { return request("read", body, 8 * 1024 * 1024); }

    private byte[] request(String action, Object body, int limit)
    {
        if (!java.util.Set.of("status", "execute", "import", "publish", "delete", "read", "lease", "stop").contains(action)) throw unavailable();
        try
        {
            if (tokenFile.isBlank()) throw unavailable();
            if (Files.size(Path.of(tokenFile)) > 4096) throw unavailable();
            String token = Files.readString(Path.of(tokenFile)).trim();
            if (token.length() < 32) throw unavailable();
            byte[] encoded = json.writeValueAsBytes(body);
            if (encoded.length > 256 * 1024) throw new ServiceException("工作空间请求超过大小限制");
            var connection = (HttpURLConnection) base.resolve("/v1/" + action).toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(40000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(encoded.length);
            try
            {
                try (var output = connection.getOutputStream()) { output.write(encoded); }
                int status = connection.getResponseCode();
                byte[] value;
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
                try (var stream = status == 200 ? connection.getInputStream() : connection.getErrorStream();
                        var output = new java.io.ByteArrayOutputStream())
                {
                    if (stream == null) throw unavailable();
                    byte[] buffer = new byte[8192];
                    int size;
                    while ((size = stream.read(buffer)) >= 0)
                    {
                        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw unavailable();
                        if (output.size() + size > limit) throw new ServiceException("工作空间响应超过大小限制");
                        output.write(buffer, 0, size);
                    }
                    value = output.toByteArray();
                }
                if (status != 200)
                {
                    JsonNode error = json.readTree(value);
                    String message = error.path("error").asText("工作空间操作失败");
                    throw new ServiceException(message.substring(0, Math.min(200, message.length())));
                }
                return value;
            }
            finally { connection.disconnect(); }
        }
        catch (ServiceException error) { throw error; }
        catch (Exception error) { throw unavailable(); }
    }

    private static ServiceException unavailable() { return new ServiceException("受控工作空间服务不可用，环境能力暂时关闭"); }
}
