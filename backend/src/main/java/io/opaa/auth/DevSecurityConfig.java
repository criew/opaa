package io.opaa.auth;

import io.opaa.security.ManagementPortMetrics;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Security configuration for local development and automated tests. Deliberately performs no
 * credential check at all — see {@link DevAuthFilter}.
 *
 * <p>Apart from how the principal is obtained, this chain is identical to {@code
 * io.opaa.account.OidcSecurityConfig}, which is the point: development and production exercise the
 * same authorization rules, the same user provisioning and the same method security.
 */
@Configuration
@Profile("dev")
@EnableMethodSecurity
public class DevSecurityConfig {

  private static final Logger log = LoggerFactory.getLogger(DevSecurityConfig.class);

  private final AuthProperties authProperties;
  private final UserService userService;

  public DevSecurityConfig(AuthProperties authProperties, UserService userService) {
    this.authProperties = authProperties;
    this.userService = userService;
  }

  @PostConstruct
  void warnAboutDisabledAuthentication() {
    log.warn(
        """

        ============================================================
        OPAA is running with the "dev" authentication profile.
        Every request is authenticated as a configured user without
        any credential check. Never use this profile for a deployment
        that is reachable by anyone but the developer running it.
        Configured users: {} | default: {}
        ============================================================""",
        authProperties.dev().users().stream().map(AuthProperties.DevUser::subject).toList(),
        authProperties.dev().defaultUser());
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      CorsConfigurationSource corsConfigurationSource,
      Clock clock,
      ObjectProvider<ManagementPortMetrics> managementPortMetrics)
      throws Exception {
    DevAuthFilter devAuthFilter = new DevAuthFilter(authProperties.dev(), clock);
    return http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .headers(SelfDisclosureDownloadFraming.frameHeaders())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/health")
                    .permitAll()
                    .requestMatchers(
                        "/actuator/health",
                        // the two probes a load balancer or a container health check reads; they
                        // answer a status, never details
                        "/actuator/health/readiness",
                        "/actuator/health/liveness",
                        "/actuator/info")
                    .permitAll()
                    // Metrics name paths, libraries and load: open on a management port of their
                    // own, which a network policy opens to the scraper alone, else only signed in.
                    .requestMatchers(ManagementPortMetrics.onManagementPort(managementPortMetrics))
                    .permitAll()
                    .requestMatchers(ManagementPortMetrics.anyPort())
                    .authenticated()
                    // Every other health group needs a token: the endpoint computes its
                    // contributors before it decides what to show, so an anonymous call to
                    // embedding-model or vector-store would trigger a real embedding call and a
                    // real similarity search - outside the rate limit, which only covers /api. Two
                    // stars, not one: the health endpoint selector matches all remaining path
                    // segments, so a single star would leave /actuator/health/<group>/<component>
                    // on anyRequest().permitAll().
                    .requestMatchers("/actuator/health/**")
                    .authenticated()
                    // Everything else of the actuator - its link list and any endpoint an operator
                    // exposes on top (env, heapdump) - needs a sign-in, on every port.
                    .requestMatchers("/actuator", "/actuator/**")
                    .authenticated()
                    .requestMatchers("/api/v1/auth/config")
                    .permitAll()
                    // Branding is readable without authentication. The sign-in page is the first
                    // thing a user sees and has to carry the operator's own product name, claim,
                    // logo and background - it renders before there is a session, so an
                    // authenticated-only endpoint could not brand it at all. What this exposes is
                    // deliberate and bounded: the name, claim, accent colour and images of the
                    // deployment - that is, which Behörde runs it, which anyone reaching its
                    // sign-in page in the first place can already tell. No user, space, library or
                    // configuration data is reachable through these paths.
                    .requestMatchers(
                        "/api/v1/branding",
                        "/api/v1/branding/logo",
                        "/api/v1/branding/login-logo",
                        "/api/v1/branding/login-background")
                    .permitAll()
                    // The self-disclosure download carries no bearer token; the one-time ticket in
                    // the form body is its only credential, as under oidc.
                    .requestMatchers(HttpMethod.POST, "/api/v1/me/self-disclosure/download")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .addFilterBefore(devAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(new UserProvisioningFilter(userService), DevAuthFilter.class)
        .build();
  }
}
