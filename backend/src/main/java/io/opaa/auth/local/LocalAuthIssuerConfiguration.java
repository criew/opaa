package io.opaa.auth.local;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the parts of the local issuer that are not plain components (ADR-0033): the password policy
 * over the settings singleton, and - in the {@code oidc} profile only, like the endpoints - the
 * {@code pcr} filter {@code OidcSecurityConfig} places after authorization. The filter is a bean
 * here and not a scanned component so the {@code @WebMvcTest} slices of the public paths, which
 * include every scanned {@code Filter}, are not forced to satisfy its dependencies.
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

  @Bean
  @Profile("oidc")
  PasswordChangeRequiredFilter passwordChangeRequiredFilter(
      LocalCredentialsRepository credentials, JsonMapper jsonMapper) {
    return new PasswordChangeRequiredFilter(credentials, jsonMapper);
  }
}
