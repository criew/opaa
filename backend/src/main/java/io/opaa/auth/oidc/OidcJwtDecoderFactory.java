package io.opaa.auth.oidc;

import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Builds the {@link JwtDecoder} for one provider (#1329) - an interface so {@link
 * OidcProviderRegistry} is testable without an identity provider on the network. {@link
 * NimbusOidcJwtDecoderFactory} is the production implementation.
 */
public interface OidcJwtDecoderFactory {

  /**
   * @throws RuntimeException when the decoder cannot be built (discovery unreachable, malformed
   *     metadata); the registry logs and skips the provider
   */
  JwtDecoder create(OidcProvider provider);
}
