package io.opaa.auth;

import io.opaa.auth.local.CsrfCookieFilter;
import io.opaa.auth.local.LocalAuthAccessDeniedHandler;
import io.opaa.auth.local.PasswordChangeRequiredFilter;
import jakarta.servlet.http.HttpServletRequest;
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
  private final JsonMapper jsonMapper;

  /**
   * The {@code pcr} filter is resolved lazily: the {@code oidc} profile always provides it ({@code
   * LocalAuthIssuerConfiguration}), and the {@code @WebMvcTest} slices that import this class to
   * assert the chain's public paths do not - they never carry a local token either.
   */
  public OidcSecurityConfig(
      UserService userService,
      AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver,
      ObjectProvider<PasswordChangeRequiredFilter> passwordChangeRequiredFilter,
      JsonMapper jsonMapper) {
    this.userService = userService;
    this.oidcAuthenticationManagerResolver = oidcAuthenticationManagerResolver;
    this.passwordChangeRequiredFilter = passwordChangeRequiredFilter;
    this.jsonMapper = jsonMapper;
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
                        "/actuator/info",
                        "/actuator/metrics",
                        "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/config")
                    .permitAll()
                    // ADR-0033: the local sign-in and the two cookie-bearing session endpoints
                    // have no bearer token yet (or no longer); change-password stays bearer-only.
                    .requestMatchers(HttpMethod.POST, LOCAL_LOGIN, LOCAL_REFRESH, LOCAL_LOGOUT)
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
