package io.opaa.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(
    name = "opaa.rate-limit.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class RateLimitConfiguration {

  @Bean
  FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
      RateLimitProperties properties, ObjectMapper objectMapper) {
    Map<String, RateLimitService> limiters = new LinkedHashMap<>();
    limiters.put(
        "/api/v1/query",
        new RateLimitService(properties.query().maxRequests(), properties.query().windowSeconds()));
    limiters.put(
        "/api/v1/indexing/trigger",
        new RateLimitService(
            properties.indexing().maxRequests(), properties.indexing().windowSeconds()));

    var registration = new FilterRegistrationBean<>(new RateLimitFilter(limiters, objectMapper));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }
}
