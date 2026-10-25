package io.opaa.installation.web;

import io.opaa.common.InstallationStatus;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * The web side of the installation in every profile: the gate ahead of every other filter, the
 * access filter right behind it, and a security chain of its own for {@code /api/v1/installation},
 * ordered before the {@code oidc} and {@code dev} chains. That chain carries no resource server, no
 * session and no CSRF - the installation token is no JWT, and checking it is {@link
 * InstallationAccessGuard}'s job; no account exists to sign in with.
 */
@Configuration
class InstallationWebConfiguration {

  @Bean
  FilterRegistrationBean<InstallationGateFilter> installationGateFilterRegistration(
      InstallationStatus status, JsonMapper jsonMapper, Clock clock) {
    var registration =
        new FilterRegistrationBean<>(new InstallationGateFilter(status, jsonMapper, clock));
    registration.addUrlPatterns("/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean
  FilterRegistrationBean<InstallationAccessFilter> installationAccessFilterRegistration(
      InstallationAccessGuard guard, JsonMapper jsonMapper, Clock clock) {
    var registration =
        new FilterRegistrationBean<>(new InstallationAccessFilter(guard, jsonMapper, clock));
    registration.addUrlPatterns("/api/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 11)
  SecurityFilterChain installationSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(
            new OrRequestMatcher(
                PathPatternRequestMatcher.withDefaults()
                    .matcher(InstallationGateFilter.INSTALLATION_PATH),
                PathPatternRequestMatcher.withDefaults()
                    .matcher(InstallationGateFilter.INSTALLATION_PATH + "/**")))
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}
