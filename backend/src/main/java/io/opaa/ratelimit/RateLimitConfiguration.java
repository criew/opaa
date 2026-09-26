package io.opaa.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.ratelimit.RateLimitFilter.Rule;
import io.opaa.ratelimit.RateLimitProperties.EndpointLimit;
import io.opaa.ratelimit.RateLimitProperties.LocalAuthLimit;
import io.opaa.ratelimit.RateLimitProperties.LocalAuthLimits;
import io.opaa.security.TrustedProxyClientIpResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
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
 *
 * <p>The two switchable self-service rules ask {@link SelfServiceEndpointAvailability} whether
 * their endpoint is served at all; the auth side supplies the answer through that interface.
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

  /**
   * The two handover endpoints of #1563 share one budget, like the outbound probes above: a
   * redemption is one preview plus one redeem by the same person, and an unauthenticated caller
   * without a valid code gets nothing out of either. The group is non-capturing on purpose - a
   * capturing one would give each path its own bucket and double the budget.
   */
  static final String LOCAL_HANDOVER_PATTERN = "^/api/v1/auth/local/handover/(?:preview|redeem)$";

  /** Keyed by the client alone: one bucket for all spaces, so switching spaces buys no budget. */
  static final String CHAT_SEARCH_PATTERN = "^/api/v1/spaces/[^/]+/chats/search$";

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
  public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
      RateLimitProperties properties,
      TrustedProxyClientIpResolver clientIpResolver,
      RateLimitMetrics metrics,
      JsonMapper jsonMapper,
      ObjectProvider<SelfServiceEndpointAvailability> selfServiceFlows) {
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
    rules.add(rule("chat-search", CHAT_SEARCH_PATTERN, properties.chatSearch()));
    LocalAuthLimits localAuth = properties.localAuth();
    rules.add(rule("local-auth-login", LOCAL_LOGIN_PATTERN, localAuth.login()));
    rules.add(rule("local-auth-refresh", LOCAL_REFRESH_PATTERN, localAuth.refresh()));
    // #1592: a switched-off flow is answered exactly like an unknown route, which has no budget -
    // counting these two while they are off would answer 429 where the unknown route answers 401.
    // Resolved per request, never at wiring time: the flows read their switches from the database.
    rules.add(
        rule(
            "local-auth-register",
            LOCAL_REGISTER_PATTERN,
            localAuth.register(),
            served(
                selfServiceFlows, SelfServiceEndpointAvailability::isSelfRegistrationAvailable)));
    rules.add(
        rule(
            "local-auth-forgot-password",
            LOCAL_FORGOT_PASSWORD_PATTERN,
            localAuth.forgotPassword(),
            served(selfServiceFlows, SelfServiceEndpointAvailability::isPasswordResetAvailable)));
    rules.add(rule("local-auth-set-password", LOCAL_SET_PASSWORD_PATTERN, localAuth.setPassword()));
    rules.add(rule("local-auth-verify-email", LOCAL_VERIFY_EMAIL_PATTERN, localAuth.verifyEmail()));
    rules.add(rule("local-auth-handover", LOCAL_HANDOVER_PATTERN, localAuth.handover()));

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
    return rule(name, pattern, limit, RateLimitFilter.ALWAYS_SERVED);
  }

  private static Rule rule(
      String name, String pattern, LocalAuthLimit limit, BooleanSupplier served) {
    return new Rule(
        name,
        pattern,
        new RateLimitService(limit.maxRequests(), limit.windowSeconds()),
        limit.hasGlobalLimit()
            ? new RateLimitService(limit.globalMaxRequests(), limit.windowSeconds())
            : null,
        served);
  }

  /**
   * Resolved through the provider on every request, not once at wiring time: forcing the bean here
   * would pull the whole local account management into the creation of this filter. No context
   * without the bean is known today; the fallback is there to fail closed rather than to serve one.
   */
  private static BooleanSupplier served(
      ObjectProvider<SelfServiceEndpointAvailability> flows,
      Predicate<SelfServiceEndpointAvailability> flow) {
    return () -> flow.test(flows.getIfAvailable(() -> SelfServiceEndpointAvailability.NONE));
  }
}
