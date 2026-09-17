package com.ruoyi.ai.service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;

@Service
public class AiOpenAiClient
{
    public List<String> listModels(String baseUrl, String token, int timeoutSeconds)
    {
        validateEndpoint(baseUrl, token);
        try
        {
            RestClient client = buildRestClient(baseUrl, token, timeoutSeconds);
            Map<?, ?> body = client.get().uri("/models").retrieve().body(Map.class);
            if (body == null || !(body.get("data") instanceof List<?> data))
            {
                throw new ServiceException("模型服务 /models 返回格式不正确");
            }
            List<String> models = new ArrayList<>();
            for (Object item : data)
            {
                if (item instanceof Map<?, ?> map && map.get("id") != null)
                {
                    String id = String.valueOf(map.get("id")).trim();
                    if (StringUtils.isNotEmpty(id))
                    {
                        models.add(id);
                    }
                }
            }
            if (models.isEmpty())
            {
                throw new ServiceException("模型服务未返回任何模型");
            }
            return models;
        }
        catch (RestClientResponseException e)
        {
            throw new ServiceException("模型服务连接失败，HTTP " + e.getStatusCode().value());
        }
        catch (ResourceAccessException e)
        {
            throw new ServiceException("模型服务连接失败或超时");
        }
    }

    public String simpleChat(String baseUrl, String token, String modelCode, int timeoutSeconds, String prompt)
    {
        validateEndpoint(baseUrl, token);
        try
        {
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .baseUrl(normalizeBaseUrl(baseUrl))
                    .apiKey(token)
                    .model(modelCode)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .maxRetries(0)
                    .parallelToolCalls(false)
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder().options(options).build();
            return model.call(prompt);
        }
        catch (Exception e)
        {
            throw new ServiceException("模型聊天测试失败：" + safeMessage(e));
        }
    }

    public String normalizeBaseUrl(String baseUrl)
    {
        String value = baseUrl == null ? "" : baseUrl.trim();
        while (value.endsWith("/"))
        {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private RestClient buildRestClient(String baseUrl, String token, int timeoutSeconds)
    {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    private void validateEndpoint(String baseUrl, String token)
    {
        if (StringUtils.isEmpty(baseUrl))
        {
            throw new ServiceException("Base URL 不能为空");
        }
        if (StringUtils.isEmpty(token))
        {
            throw new ServiceException("Token 不能为空");
        }
        try
        {
            URI uri = URI.create(normalizeBaseUrl(baseUrl));
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || StringUtils.isEmpty(uri.getHost()))
            {
                throw new IllegalArgumentException("unsupported endpoint");
            }
        }
        catch (Exception e)
        {
            throw new ServiceException("Base URL 必须是有效的 HTTP/HTTPS 地址");
        }
    }

    private String safeMessage(Exception e)
    {
        String message = e.getMessage();
        if (StringUtils.isEmpty(message))
        {
            return e.getClass().getSimpleName();
        }
        if (message.length() > 180)
        {
            return message.substring(0, 180);
        }
        return message;
    }
}
