package io.opaa.auth.local;

import io.opaa.security.LocalAuthKeyService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link LocalAuthProperties} and derives the local issuer's keys from its secret. Active in
 * every profile: the {@code dev} profile carries a clearly marked non-production secret in {@code
 * application.yml} so tests and {@code bootRun} have working keys; the {@code oidc} profile
 * additionally runs {@link LocalAuthSecretGuard}.
 */
@Configuration
@EnableConfigurationProperties(LocalAuthProperties.class)
public class LocalAuthConfiguration {

  @Bean
  public LocalAuthKeyService localAuthKeyService(LocalAuthProperties properties) {
    return new LocalAuthKeyService(properties.jwtSecret());
  }
}
