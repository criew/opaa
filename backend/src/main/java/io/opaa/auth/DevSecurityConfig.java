package io.opaa.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .addFilterBefore(devAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(new UserProvisioningFilter(userService), DevAuthFilter.class)
        .build();
  }
}
