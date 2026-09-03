package io.opaa.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
 * <p>Apart from how the principal is obtained, this chain is identical to {@link
 * OidcSecurityConfig}, which is the point: development and production exercise the same
 * authorization rules, the same user provisioning and the same method security.
 */
@Configuration
@Profile("dev")
@EnableConfigurationProperties(AuthProperties.class)
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
      HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
    DevAuthFilter devAuthFilter = new DevAuthFilter(authProperties.dev());
    return http.csrf(AbstractHttpConfigurer::disable)
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
        .addFilterBefore(devAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(new UserProvisioningFilter(userService), DevAuthFilter.class)
        .build();
  }
}
