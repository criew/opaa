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
 * The security chain of the S3 event intake (ADR-0027, Entscheidung 6), in every profile: ordered
 * before the {@code oidc} and {@code dev} chains and matching only {@code POST
 * /api/v1/libraries/*}/s3-events}. It carries no resource server, no session and no CSRF - an
 * object store authenticates with the library's event token as {@code Authorization: Bearer}, and
 * the resource server's bearer filter of the {@code oidc} chain would reject that token as a
 * malformed JWT before the endpoint ever ran. Checking the token is the endpoint's own job ({@code
 * S3EventService#accept}); nothing is readable through this path.
 */
@Configuration
public class S3EventSecurityConfig {

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 10)
  SecurityFilterChain s3EventSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.securityMatcher(
            PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.POST, "/api/v1/libraries/*/s3-events"))
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}
