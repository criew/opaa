package io.opaa.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@Profile("oidc")
@EnableMethodSecurity
public class OidcSecurityConfig {

  private final UserService userService;

  public OidcSecurityConfig(UserService userService) {
    this.userService = userService;
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, CorsConfigurationSource corsConfigurationSource) throws Exception {
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
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
        .addFilterAfter(
            new UserProvisioningFilter(userService), BearerTokenAuthenticationFilter.class)
        .build();
  }
}
