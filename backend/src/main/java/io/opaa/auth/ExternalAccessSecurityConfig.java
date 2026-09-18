package io.opaa.auth;

import io.opaa.externalaccess.ExternalAccessNetworkPolicy;
import io.opaa.externalaccess.token.ExternalAccessTokenAuthenticator;
import io.opaa.externalaccess.token.ExternalAccessTokenService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * The security chain of the external-access channel (ADR-0035), in every profile.
 *
 * <p>It matches on the <b>bearer value</b>, not on a path: every request presenting a value with
 * the access-token prefix lands here, wherever it is aimed. That is what makes "a token reaches the
 * allowed paths and nothing else" a property of the chain instead of a rule someone has to remember
 * - an access token aimed at an administration, indexing, upload or rights endpoint is refused here
 * with {@code 403} and never reaches the {@code oidc} or {@code dev} chain at all. Under {@code
 * local,dev} that also keeps {@code DevAuthFilter} from authenticating the call as the development
 * user.
 *
 * <p>No resource server, no session, no CSRF: the value is opaque, the bearer filter of the {@code
 * oidc} chain would reject it as a malformed JWT, and a bearer-only call carries no cookie a
 * cross-site request could ride on.
 */
@Configuration
public class ExternalAccessSecurityConfig {

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 20)
  SecurityFilterChain externalAccessSecurityFilterChain(
      HttpSecurity http,
      ExternalAccessTokenAuthenticator authenticator,
      ExternalAccessTokenService tokenService,
      ExternalAccessNetworkPolicy networkPolicy,
      ExternalAccessPathAllowlist allowlist,
      JsonMapper jsonMapper,
      Clock clock)
      throws Exception {
    http.securityMatcher(ExternalAccessTokenAuthenticationFilter::carriesAccessToken)
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(
            new ExternalAccessTokenAuthenticationFilter(
                authenticator, tokenService, networkPolicy, jsonMapper, clock),
            AuthorizationFilter.class)
        .authorizeHttpRequests(
            auth -> {
              for (RequestMatcher matcher : allowlist.matchers()) {
                auth.requestMatchers(matcher)
                    .hasAuthority(ExternalAccessTokenAuthenticationFilter.AUTHORITY);
              }
              auth.anyRequest().denyAll();
            });
    return http.build();
  }
}
