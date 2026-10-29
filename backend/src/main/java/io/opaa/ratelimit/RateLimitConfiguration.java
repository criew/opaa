package io.opaa.ratelimit;

import static io.opaa.ratelimit.RateLimitFilter.Counting.AUTHENTICATED;
import static io.opaa.ratelimit.RateLimitFilter.Counting.AUTHENTICATED_BY_HANDLER;

import io.micrometer.core.instrument.MeterRegistry;
import io.opaa.observability.RateLimitMetrics;
import io.opaa.ratelimit.RateLimitFilter.Counting;
import io.opaa.ratelimit.RateLimitFilter.Rule;
import io.opaa.ratelimit.RateLimitProperties.EndpointLimit;
import io.opaa.ratelimit.RateLimitProperties.LocalAuthLimit;
import io.opaa.ratelimit.RateLimitProperties.LocalAuthLimits;
import io.opaa.security.TrustedProxyClientIpResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
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
 * their endpoint is served at all.
 *
 * <p>The global ceilings count for the whole installation in {@link InstallationCounters}, the
 * budgets per client per instance (ADR-0053, Entscheidung 12).
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

  /** The endpoints of the local sign-in and self-service (ADR-0033, Entscheidung 9). */
  static final String LOCAL_LOGIN_PATTERN = "^/api/v1/auth/local/login$";

  static final String LOCAL_REFRESH_PATTERN = "^/api/v1/auth/local/refresh$";
  static final String LOCAL_REGISTER_PATTERN = "^/api/v1/auth/local/register$";
  static final String LOCAL_FORGOT_PASSWORD_PATTERN = "^/api/v1/auth/local/forgot-password$";
  static final String LOCAL_SET_PASSWORD_PATTERN = "^/api/v1/auth/local/set-password$";
  static final String LOCAL_VERIFY_EMAIL_PATTERN = "^/api/v1/auth/local/verify-email$";

  /**
   * The two handover endpoints share one budget, like the outbound probes above: a redemption is
   * one preview plus one redeem by the same person, and an unauthenticated caller without a valid
   * code gets nothing out of either. The group is non-capturing on purpose - a capturing one would
   * give each path its own bucket and double the budget.
   */
  static final String LOCAL_HANDOVER_PATTERN = "^/api/v1/auth/local/handover/(?:preview|redeem)$";

  /** Keyed by the client alone: one bucket for all spaces, so switching spaces buys no budget. */
  static final String CHAT_SEARCH_PATTERN = "^/api/v1/spaces/[^/]+/chats/search$";

  /**
   * The search and the fetch of one of its hits share one bucket per client, keyed by the client
   * alone: another hit id buys no fresh budget. The listing of searchable libraries, the
   * availability and the filter options next to it stay outside.
   */
  static final String SEARCH_PATTERN = "^/api/v1/search(?:/hits/[^/]+)?$";

  /** The dictation; the availability next to it stays outside. */
  static final String TRANSCRIPTION_PATTERN = "^/api/v1/transcriptions$";

  /**
   * The upload into a library, keyed by the client alone and for {@code POST} only: another library
   * buys no fresh budget, and the document listing on the same path stays outside.
   */
  static final String DOCUMENT_UPLOAD_PATTERN = "^/api/v1/libraries/[^/]+/documents$";

  @Bean
  TrustedProxyClientIpResolver clientIpResolver(RateLimitProperties properties) {
    return new TrustedProxyClientIpResolver(properties.trustedProxyCidrs());
  }

  @Bean
  RateLimitMetrics rateLimitMetrics(MeterRegistry meterRegistry) {
    return new RateLimitMetrics(meterRegistry);
  }

  /**
   * Marks requests the security chain authenticated, for {@link RateLimitFilter}; ordered directly
   * behind the chain, whatever order the chain is given.
   */
  @Bean
  @ConditionalOnProperty(
      name = "opaa.rate-limit.enabled",
      havingValue = "true",
      matchIfMissing = true)
  FilterRegistrationBean<AuthenticatedRequestMarkerFilter> authenticatedRequestMarkerRegistration(
      ObjectProvider<SecurityFilterProperties> securityFilter) {
    var registration = new FilterRegistrationBean<>(new AuthenticatedRequestMarkerFilter());
    registration.addUrlPatterns("/api/*");
    registration.setOrder(
        securityFilter.getIfAvailable(SecurityFilterProperties::new).getOrder() + 1);
    return registration;
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
      ObjectProvider<SelfServiceEndpointAvailability> selfServiceFlows,
      InstallationCounters counters,
      Clock clock) {
    // The per-library indexing trigger (POST /api/v1/libraries/{libraryId}/indexing) carries a
    // variable path segment, so its rule is a regex rather than a plain prefix - see
    // RateLimitFilter.Rule's Javadoc. The trailing $ deliberately excludes the sibling status
    // endpoint (GET .../indexing/status), which - like the old GET /api/v1/indexing/status - was
    // never rate-limited. The capture group around the library id lets RateLimitFilter key the
    // per-IP limiter by library, so triggering indexing for one library doesn't block a different
    // library from the same client.
    String indexingTriggerPattern = "^/api/v1/libraries/([^/]+)/indexing$";
    // No capture group here (unlike the indexing trigger above): these probes carry no library -
    // there is none yet - so the per-IP limiter is keyed by the client alone. The alternation is
    // non-capturing on purpose: a capturing group would give each path its own bucket and multiply
    // the outbound probe budget. The listing of a source before it is saved is the same kind of
    // synchronous outbound probe as the connection test and shares its limit.
    String sourceTestPattern = "^/api/v1/(?:libraries/source-test|source-types/[^/]+/browse)$";
    // A flat pattern, mirroring source-test above rather than the per-library indexing trigger's
    // capture group - unlike triggering an indexing run, "Im Dokument öffnen" is a routine
    // per-document click any VIEWER can make on any document, so keying the limiter by document id
    // would let the same caller bypass the limit simply by clicking a different document each time.
    String documentContentPattern = "^/api/v1/documents/[^/]+/content$";
    // The push intake is the only POST under /api/v1 reachable without a session. The capture
    // group keys the per-IP limiter by library, like the indexing trigger: one source notifying
    // several libraries is several senders, not one. Pushes that do not authenticate count per
    // address, whichever library they name (Counting.AUTHENTICATED_BY_HANDLER).
    String webhookPattern = "^/api/v1/libraries/([^/]+)/push$";

    // These endpoints sit behind the sign-in (the push intake behind its secret): only requests
    // that authenticated keep their token of the global ceiling.
    List<Rule> rules = new ArrayList<>();
    rules.add(rule(counters, "query", "^/api/v1/query", properties.query(), AUTHENTICATED));
    rules.add(
        rule(counters, "indexing", indexingTriggerPattern, properties.indexing(), AUTHENTICATED));
    rules.add(
        rule(counters, "source-test", sourceTestPattern, properties.sourceTest(), AUTHENTICATED));
    rules.add(
        rule(
            counters,
            "document-content",
            documentContentPattern,
            properties.documentContent(),
            AUTHENTICATED));
    rules.add(
        rule(counters, "webhook", webhookPattern, properties.webhook(), AUTHENTICATED_BY_HANDLER));
    rules.add(
        rule(counters, "chat-search", CHAT_SEARCH_PATTERN, properties.chatSearch(), AUTHENTICATED));
    rules.add(rule(counters, "search", SEARCH_PATTERN, properties.search(), AUTHENTICATED));
    rules.add(
        rule(
            counters,
            "transcription",
            TRANSCRIPTION_PATTERN,
            properties.transcription(),
            AUTHENTICATED));
    rules.add(
        rule(
            counters,
            "document-upload",
            DOCUMENT_UPLOAD_PATTERN,
            properties.documentUpload(),
            AUTHENTICATED,
            "POST"));
    LocalAuthLimits localAuth = properties.localAuth();
    rules.add(rule(counters, "local-auth-login", LOCAL_LOGIN_PATTERN, localAuth.login()));
    rules.add(rule(counters, "local-auth-refresh", LOCAL_REFRESH_PATTERN, localAuth.refresh()));
    // A switched-off flow is answered exactly like an unknown route, which has no budget - counting
    // these two while they are off would answer 429 where the unknown route answers 401. Resolved
    // per request, never at wiring time: the flows read their switches from the database.
    rules.add(
        rule(
            counters,
            "local-auth-register",
            LOCAL_REGISTER_PATTERN,
            localAuth.register(),
            served(
                selfServiceFlows, SelfServiceEndpointAvailability::isSelfRegistrationAvailable)));
    rules.add(
        rule(
            counters,
            "local-auth-forgot-password",
            LOCAL_FORGOT_PASSWORD_PATTERN,
            localAuth.forgotPassword(),
            served(selfServiceFlows, SelfServiceEndpointAvailability::isPasswordResetAvailable)));
    rules.add(
        rule(
            counters,
            "local-auth-set-password",
            LOCAL_SET_PASSWORD_PATTERN,
            localAuth.setPassword()));
    rules.add(
        rule(
            counters,
            "local-auth-verify-email",
            LOCAL_VERIFY_EMAIL_PATTERN,
            localAuth.verifyEmail()));
    rules.add(rule(counters, "local-auth-handover", LOCAL_HANDOVER_PATTERN, localAuth.handover()));

    var registration =
        new FilterRegistrationBean<>(
            new RateLimitFilter(rules, clientIpResolver, metrics, jsonMapper, clock));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }

  private static Rule rule(
      InstallationCounters counters,
      String name,
      String pattern,
      EndpointLimit limit,
      Counting counting) {
    return rule(counters, name, pattern, limit, counting, null);
  }

  private static Rule rule(
      InstallationCounters counters,
      String name,
      String pattern,
      EndpointLimit limit,
      Counting counting,
      String method) {
    return new Rule(
        name,
        pattern,
        new RateLimitService(limit.maxRequests(), limit.windowSeconds()),
        new InstallationRateLimit(counters, name, limit.globalMaxRequests(), limit.windowSeconds()),
        RateLimitFilter.ALWAYS_SERVED,
        counting,
        limit.hasAccountLimit()
            ? new RateLimitService(limit.maxRequestsPerAccount(), limit.windowSeconds())
            : null,
        method);
  }

  private static Rule rule(
      InstallationCounters counters, String name, String pattern, LocalAuthLimit limit) {
    return rule(counters, name, pattern, limit, RateLimitFilter.ALWAYS_SERVED);
  }

  private static Rule rule(
      InstallationCounters counters,
      String name,
      String pattern,
      LocalAuthLimit limit,
      BooleanSupplier served) {
    return new Rule(
        name,
        pattern,
        new RateLimitService(limit.maxRequests(), limit.windowSeconds()),
        limit.hasGlobalLimit()
            ? new InstallationRateLimit(
                counters, name, limit.globalMaxRequests(), limit.windowSeconds())
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
