package io.opaa.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.api.RateLimitFilter.Rule;
import io.opaa.api.RateLimitProperties.EndpointLimit;
import io.opaa.api.RateLimitProperties.LocalAuthLimit;
import io.opaa.api.RateLimitProperties.LocalAuthLimits;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.security.TrustedProxyClientIpResolver;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the rate limits. The client-address resolution and the metrics exist in every profile and
 * whether or not limiting is switched on - the resolution also serves the network restriction of
 * local system administrators (ADR-0033, Entscheidung 9); only the filter itself honours {@code
 * opaa.rate-limit.enabled}.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

  /**
   * The endpoints of the local sign-in (ADR-0033, Entscheidung 9). {@code register}, {@code
   * forgot-password} and {@code set-password} arrive with #1538 under exactly these paths; their
   * rules stand ready here.
   */
  static final String LOCAL_LOGIN_PATTERN = "^/api/v1/auth/local/login$";

  static final String LOCAL_REFRESH_PATTERN = "^/api/v1/auth/local/refresh$";
  static final String LOCAL_REGISTER_PATTERN = "^/api/v1/auth/local/register$";
  static final String LOCAL_FORGOT_PASSWORD_PATTERN = "^/api/v1/auth/local/forgot-password$";
  static final String LOCAL_SET_PASSWORD_PATTERN = "^/api/v1/auth/local/set-password$";
  static final String LOCAL_VERIFY_EMAIL_PATTERN = "^/api/v1/auth/local/verify-email$";

  @Bean
  TrustedProxyClientIpResolver clientIpResolver(RateLimitProperties properties) {
    return new TrustedProxyClientIpResolver(properties.trustedProxyCidrs());
  }

  @Bean
  RateLimitMetrics rateLimitMetrics(MeterRegistry meterRegistry) {
    return new RateLimitMetrics(meterRegistry);
  }

  @Bean
  @ConditionalOnProperty(
      name = "opaa.rate-limit.enabled",
      havingValue = "true",
      matchIfMissing = true)
  FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
      RateLimitProperties properties,
      TrustedProxyClientIpResolver clientIpResolver,
      RateLimitMetrics metrics,
      JsonMapper jsonMapper) {
    // #478: the per-library indexing trigger (POST /api/v1/libraries/{libraryId}/indexing) carries
    // a variable path segment, so its rule is a regex rather than a plain prefix - see
    // RateLimitFilter.Rule's Javadoc. The trailing $ deliberately excludes the sibling status
    // endpoint (GET .../indexing/status), which - like the old GET /api/v1/indexing/status -
    // was never rate-limited. The capture group around the library id lets RateLimitFilter key the
    // per-IP limiter by library, so triggering indexing for one library doesn't block a different
    // library from the same client.
    String indexingTriggerPattern = "^/api/v1/libraries/([^/]+)/indexing$";
    // No capture group here (unlike the indexing trigger above): these probes carry no library -
    // there is none yet - so the per-IP limiter is keyed by the client alone. The alternation is
    // non-capturing on purpose: a capturing group would give each path its own bucket and double
    // the outbound probe budget. The Confluence space listing (ADR-0023) and the S3 bucket
    // listing (ADR-0027) are the same kind of synchronous outbound probe as the connection test
    // and share its limit.
    String sourceTestPattern = "^/api/v1/libraries/(?:source-test|confluence/spaces|s3/buckets)$";
    // #748 review, finding 1: a flat pattern, mirroring source-test above rather than the
    // per-library indexing trigger's capture group - unlike triggering an indexing run, "Im
    // Dokument öffnen" is a routine per-document click any VIEWER can make on any document, so
    // keying the limiter by document id would let the same caller bypass the limit simply by
    // clicking a different document each time.
    String documentContentPattern = "^/api/v1/documents/[^/]+/content$";
    // #1140: the webhook intakes are the only POSTs under /api/v1 reachable without a session. The
    // capture group keys the per-IP limiter by library, like the indexing trigger: one instance
    // notifying several libraries is several senders, not one.
    // ADR-0027, Entscheidung 6: the S3 event intake shares the pot - same posture, same limits.
    String webhookPattern = "^/api/v1/libraries/([^/]+)/(?:confluence-webhook|s3-events)$";

    List<Rule> rules = new ArrayList<>();
    rules.add(rule("query", "^/api/v1/query", properties.query()));
    rules.add(rule("indexing", indexingTriggerPattern, properties.indexing()));
    rules.add(rule("source-test", sourceTestPattern, properties.sourceTest()));
    rules.add(rule("document-content", documentContentPattern, properties.documentContent()));
    rules.add(rule("webhook", webhookPattern, properties.webhook()));
    LocalAuthLimits localAuth = properties.localAuth();
    rules.add(rule("local-auth-login", LOCAL_LOGIN_PATTERN, localAuth.login()));
    rules.add(rule("local-auth-refresh", LOCAL_REFRESH_PATTERN, localAuth.refresh()));
    rules.add(rule("local-auth-register", LOCAL_REGISTER_PATTERN, localAuth.register()));
    rules.add(
        rule(
            "local-auth-forgot-password",
            LOCAL_FORGOT_PASSWORD_PATTERN,
            localAuth.forgotPassword()));
    rules.add(rule("local-auth-set-password", LOCAL_SET_PASSWORD_PATTERN, localAuth.setPassword()));
    rules.add(rule("local-auth-verify-email", LOCAL_VERIFY_EMAIL_PATTERN, localAuth.verifyEmail()));

    var registration =
        new FilterRegistrationBean<>(
            new RateLimitFilter(rules, clientIpResolver, metrics, jsonMapper));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }

  private static Rule rule(String name, String pattern, EndpointLimit limit) {
    return new Rule(
        name,
        pattern,
        new RateLimitService(limit.maxRequests(), limit.windowSeconds()),
        new RateLimitService(limit.globalMaxRequests(), limit.windowSeconds()));
  }

  private static Rule rule(String name, String pattern, LocalAuthLimit limit) {
    return new Rule(
        name,
        pattern,
        new RateLimitService(limit.maxRequests(), limit.windowSeconds()),
        limit.hasGlobalLimit()
            ? new RateLimitService(limit.globalMaxRequests(), limit.windowSeconds())
            : null);
  }
}
