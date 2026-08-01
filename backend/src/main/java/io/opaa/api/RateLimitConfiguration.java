package io.opaa.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(
    name = "opaa.rate-limit.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RateLimitConfiguration {

  @Bean
  FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
      RateLimitProperties properties, JsonMapper jsonMapper) {
    Map<String, RateLimitService> perIpLimiters = new LinkedHashMap<>();
    perIpLimiters.put(
        "/api/v1/query",
        new RateLimitService(properties.query().maxRequests(), properties.query().windowSeconds()));
    perIpLimiters.put(
        "/api/v1/indexing/trigger",
        new RateLimitService(
            properties.indexing().maxRequests(), properties.indexing().windowSeconds()));

    Map<String, RateLimitService> globalLimiters = new LinkedHashMap<>();
    globalLimiters.put(
        "/api/v1/query",
        new RateLimitService(
            properties.query().globalMaxRequests(), properties.query().windowSeconds()));
    globalLimiters.put(
        "/api/v1/indexing/trigger",
        new RateLimitService(
            properties.indexing().globalMaxRequests(), properties.indexing().windowSeconds()));

    var registration =
        new FilterRegistrationBean<>(
            new RateLimitFilter(perIpLimiters, globalLimiters, jsonMapper));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }
}
