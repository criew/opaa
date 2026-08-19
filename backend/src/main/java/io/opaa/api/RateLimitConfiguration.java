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
    // #478: the per-library indexing trigger (POST /api/v1/libraries/{libraryId}/indexing) carries
    // a variable path segment, so its rule is a regex rather than a plain prefix - see
    // RateLimitFilter's constructor Javadoc. The trailing $ deliberately excludes the sibling
    // status endpoint (GET .../indexing/status), which - like the old GET /api/v1/indexing/status -
    // was never rate-limited. The capture group around the library id lets RateLimitFilter key the
    // per-IP limiter by library, so triggering indexing for one library doesn't block a different
    // library from the same client.
    String indexingTriggerPattern = "^/api/v1/libraries/([^/]+)/indexing$";
    // #514/PR #537 review, finding 3: a plain literal, not a regex capture group like the
    // indexing trigger above - source-test carries no library (there is none yet), so there is
    // nothing to key a per-library limiter by.
    String sourceTestPattern = "^/api/v1/libraries/source-test$";

    Map<String, RateLimitService> perIpLimiters = new LinkedHashMap<>();
    perIpLimiters.put(
        "^/api/v1/query",
        new RateLimitService(properties.query().maxRequests(), properties.query().windowSeconds()));
    perIpLimiters.put(
        indexingTriggerPattern,
        new RateLimitService(
            properties.indexing().maxRequests(), properties.indexing().windowSeconds()));
    perIpLimiters.put(
        sourceTestPattern,
        new RateLimitService(
            properties.sourceTest().maxRequests(), properties.sourceTest().windowSeconds()));

    Map<String, RateLimitService> globalLimiters = new LinkedHashMap<>();
    globalLimiters.put(
        "^/api/v1/query",
        new RateLimitService(
            properties.query().globalMaxRequests(), properties.query().windowSeconds()));
    globalLimiters.put(
        indexingTriggerPattern,
        new RateLimitService(
            properties.indexing().globalMaxRequests(), properties.indexing().windowSeconds()));
    globalLimiters.put(
        sourceTestPattern,
        new RateLimitService(
            properties.sourceTest().globalMaxRequests(), properties.sourceTest().windowSeconds()));

    var registration =
        new FilterRegistrationBean<>(
            new RateLimitFilter(perIpLimiters, globalLimiters, jsonMapper));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }
}
