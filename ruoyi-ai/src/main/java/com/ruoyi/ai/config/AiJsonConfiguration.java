package com.ruoyi.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Spring Boot 4 in the current RuoYi baseline does not expose the legacy
 * com.fasterxml Jackson ObjectMapper as an application bean. Spring AI still
 * uses it internally, so the AI module keeps a dedicated mapper for its own
 * tool schemas and persisted context payloads.
 */
@Configuration(proxyBeanMethods = false)
public class AiJsonConfiguration
{
    @Bean("aiObjectMapper")
    public ObjectMapper aiObjectMapper()
    {
        return new ObjectMapper();
    }
}
