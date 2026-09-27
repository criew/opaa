package io.opaa.account;

import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.OidcProviderRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the parts of the local issuer that are not plain components (ADR-0033): the password policy
 * over the settings singleton, the resolver {@link OidcSecurityConfig} hands to the resource
 * server, and - in the {@code oidc} profile only, like the endpoints - the {@code pcr} filter
 * {@code OidcSecurityConfig} places after authorization. The filter is a bean here and not a
 * scanned component so the {@code @WebMvcTest} slices of the public paths, which include every
 * scanned {@code Filter}, are not forced to satisfy its dependencies.
 */
@Configuration
public class LocalAuthIssuerConfiguration {

  @Bean
  PasswordPolicy passwordPolicy(LocalAuthSettingsRepository settings) {
    return new PasswordPolicy(
        () ->
            settings
                .findSingleton()
                .map(LocalAuthSettings::getPasswordMinLength)
                .orElseGet(() -> LocalAuthSettings.Values.defaults().passwordMinLength()));
  }

  /**
   * ADR-0033, Entscheidung 8: {@link JwtIssuerAuthenticationManagerResolver} reads the token's
   * {@code iss} (without verifying it); the local issuer sits in front of the provider registry
   * with its fixed HS256 decoder, independent of any provider row and of the management switch, so
   * a local {@code SYSTEM_ADMIN} stays verifiable while the management is off.
   */
  @Bean
  AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver(
      OidcProviderRegistry registry, LocalAccessTokenDecoder localAccessTokenDecoder) {
    return new JwtIssuerAuthenticationManagerResolver(
        new LocalIssuerAuthenticationManagerResolver(registry, localAccessTokenDecoder));
  }

  @Bean
  @Profile("oidc")
  PasswordChangeRequiredFilter passwordChangeRequiredFilter(
      LocalCredentialsRepository credentials, JsonMapper jsonMapper) {
    return new PasswordChangeRequiredFilter(credentials, jsonMapper);
  }
}
