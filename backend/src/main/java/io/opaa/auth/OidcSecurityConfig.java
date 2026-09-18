package io.opaa.auth;

import io.opaa.auth.local.CsrfCookieFilter;
import io.opaa.auth.local.LocalAuthAccessDeniedHandler;
import io.opaa.auth.local.LocalSelfServiceAvailability;
import io.opaa.auth.local.PasswordChangeRequiredFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@Profile("oidc")
@EnableMethodSecurity
public class OidcSecurityConfig {

  private static final String LOCAL_LOGIN = "/api/v1/auth/local/login";
  private static final String LOCAL_REFRESH = "/api/v1/auth/local/refresh";
  private static final String LOCAL_LOGOUT = "/api/v1/auth/local/logout";
  private static final String LOCAL_SET_PASSWORD = "/api/v1/auth/local/set-password";
  private static final String LOCAL_FORGOT_PASSWORD = "/api/v1/auth/local/forgot-password";
  private static final String LOCAL_REGISTER = "/api/v1/auth/local/register";
  private static final String LOCAL_VERIFY_EMAIL = "/api/v1/auth/local/verify-email";
  private static final String LOCAL_HANDOVER_PREVIEW = "/api/v1/auth/local/handover/preview";
  private static final String LOCAL_HANDOVER_REDEEM = "/api/v1/auth/local/handover/redeem";

  /**
   * ADR-0033, Entscheidung 7: the double-submit CSRF token is required exactly where the refresh
   * cookie carries the session - refresh and logout. Every other endpoint is bearer-only and stays
   * CSRF-free. Matched on the decoded request path, the same way the handler mapping matches, so a
   * percent-encoded spelling of the path cannot reach the handler past the check.
   */
  static final RequestMatcher LOCAL_COOKIE_ENDPOINTS =
      new OrRequestMatcher(
          PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, LOCAL_REFRESH),
          PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, LOCAL_LOGOUT));

  private final UserService userService;
  private final AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver;
  private final ObjectProvider<PasswordChangeRequiredFilter> passwordChangeRequiredFilter;
  private final ObjectProvider<LocalSelfServiceAvailability> selfServiceFlows;
  private final JsonMapper jsonMapper;

  /**
   * The {@code pcr} filter and the self-service availability are resolved lazily: the {@code oidc}
   * profile always provides both ({@code LocalAuthIssuerConfiguration}, {@code
   * LocalSelfServiceService}), and the {@code @WebMvcTest} slices that import this class to assert
   * the chain's public paths do not - they never carry a local token either, and without the
   * availability no self-service flow is served.
   */
  public OidcSecurityConfig(
      UserService userService,
      AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver,
      ObjectProvider<PasswordChangeRequiredFilter> passwordChangeRequiredFilter,
      ObjectProvider<LocalSelfServiceAvailability> selfServiceFlows,
      JsonMapper jsonMapper) {
    this.userService = userService;
    this.oidcAuthenticationManagerResolver = oidcAuthenticationManagerResolver;
    this.passwordChangeRequiredFilter = passwordChangeRequiredFilter;
    this.selfServiceFlows = selfServiceFlows;
    this.jsonMapper = jsonMapper;
  }

  /**
   * ADR-0033, Entscheidung 11: a switched-off self-service flow is not permitted here, so it falls
   * through to the {@code /api/**} rule below - the very rule an unknown route falls under. Its
   * answer is therefore not merely <em>like</em> the one an unknown route gets, it is produced by
   * the same authorization decision and the same entry point, headers included (#1592). Whether a
   * flow is served stays readable through {@code GET /api/v1/auth/config} alone; the path check
   * comes first, so no other request pays for the switch lookup.
   */
  private RequestMatcher servedFlow(String path, Predicate<LocalSelfServiceAvailability> flow) {
    RequestMatcher route = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path);
    return request ->
        route.matches(request)
            && flow.test(selfServiceFlows.getIfAvailable(() -> LocalSelfServiceAvailability.NONE));
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .requireCsrfProtectionMatcher(LOCAL_COOKIE_ENDPOINTS))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        // a refused double submit answers with its own code, so the SPA tells it apart from the
        // 403 of the pcr filter; every other access denial keeps GlobalExceptionHandler's wording
        .exceptionHandling(
            handling -> handling.accessDeniedHandler(new LocalAuthAccessDeniedHandler(jsonMapper)))
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/health")
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        // the two probes a load balancer or a container health check reads; they
                        // answer a status, never details (#1710)
                        "/actuator/health/readiness",
                        "/actuator/health/liveness",
                        "/actuator/info",
                        "/actuator/metrics",
                        "/actuator/prometheus")
                    .permitAll()
                    // Every other health group needs a token (#1710): the endpoint computes its
                    // contributors before it decides what to show, so an anonymous call to
                    // embedding-model or vector-store would trigger a real embedding call and a
                    // real similarity search - outside the rate limit, which only covers /api.
                    // Two stars, not one: the health endpoint selector matches all remaining path
                    // segments, so a single star would leave /actuator/health/<group>/<component>
                    // on anyRequest().permitAll().
                    .requestMatchers("/actuator/health/**")
                    .authenticated()
                    .requestMatchers("/api/v1/auth/config")
                    .permitAll()
                    // ADR-0033: the local sign-in, the two cookie-bearing session endpoints and
                    // the self-service of #1538 (link redemption, e-mail confirmation) have no
                    // bearer token yet (or no longer); change-password stays bearer-only.
                    // ADR-0033, Entscheidung 12: the two handover endpoints of #1563 must stay
                    // bearer-free - a provider token in the header would be provisioned into a new
                    // account by UserProvisioningFilter before the controller runs, which is the
                    // one thing the redemption has to find absent. They verify the provider token
                    // from the body themselves (LocalHandoverService).
                    .requestMatchers(
                        HttpMethod.POST,
                        LOCAL_LOGIN,
                        LOCAL_REFRESH,
                        LOCAL_LOGOUT,
                        LOCAL_SET_PASSWORD,
                        LOCAL_VERIFY_EMAIL,
                        LOCAL_HANDOVER_PREVIEW,
                        LOCAL_HANDOVER_REDEEM)
                    .permitAll()
                    // #1592: the two switchable flows are public only while they are switched on;
                    // switched off they fall through to /api/** below - see servedFlow above.
                    .requestMatchers(
                        servedFlow(
                            LOCAL_FORGOT_PASSWORD,
                            LocalSelfServiceAvailability::isPasswordResetAvailable),
                        servedFlow(
                            LOCAL_REGISTER,
                            LocalSelfServiceAvailability::isSelfRegistrationAvailable))
                    .permitAll()
                    // #582/#583: branding is readable without authentication. The sign-in
                    // page is the first thing a user sees and has to carry the operator's own
                    // product name, claim and logo - it renders before there is a session, so an
                    // authenticated-only endpoint could not brand it at all. What this exposes is
                    // deliberate and bounded: the name, claim, accent colour and logo of the
                    // deployment - that is, which Behörde runs it, which anyone reaching its
                    // sign-in page in the first place can already tell. No user, space, library or
                    // configuration data is reachable through either path.
                    .requestMatchers("/api/v1/branding", "/api/v1/branding/logo")
                    .permitAll()
                    // #1140: a Confluence instance or Automation rule has no session - the
                    // notification authenticates itself with the library's own webhook secret
                    // (ConfluenceWebhookService); nothing is readable through this path, and a
                    // request without a valid secret is answered 401 there.
                    .requestMatchers(HttpMethod.POST, "/api/v1/libraries/*/confluence-webhook")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        // ADR-0025: one AuthenticationManager per enabled provider, resolved per request from the
        // token's issuer (io.opaa.auth.oidc.OidcProviderRegistry) - not a single static JwtDecoder.
        .oauth2ResourceServer(
            oauth2 -> oauth2.authenticationManagerResolver(oidcAuthenticationManagerResolver))
        .addFilterAfter(
            new UserProvisioningFilter(userService), BearerTokenAuthenticationFilter.class);
    // ADR-0033, Entscheidung 8: after authorization, so it sees the authenticated local token and
    // answers 403 PASSWORD_CHANGE_REQUIRED outside /api/v1/auth/local/ while pcr is set.
    PasswordChangeRequiredFilter pcrFilter = passwordChangeRequiredFilter.getIfAvailable();
    if (pcrFilter != null) {
      http.addFilterAfter(pcrFilter, AuthorizationFilter.class);
    }
    return http.build();
  }
}
