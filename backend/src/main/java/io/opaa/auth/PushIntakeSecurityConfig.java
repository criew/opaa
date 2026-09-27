package io.opaa.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * The security chain of the push intake (ADR-0027, Entscheidung 6; ADR-0038), in every profile:
 * ordered before the {@code oidc} and {@code dev} chains and matching only {@code POST
 * /api/v1/libraries/*}/push}. It carries no resource server, no session and no CSRF - an object
 * store authenticates with the library's push secret as {@code Authorization: Bearer}, and the
 * resource server's bearer filter of the {@code oidc} chain would reject that secret as a malformed
 * JWT before the endpoint ever ran. Checking the secret is the connector's own job; nothing is
 * readable through this path.
 */
@Configuration
public class PushIntakeSecurityConfig {

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 10)
  SecurityFilterChain pushIntakeSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(
            PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.POST, "/api/v1/libraries/*/push"))
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}
