package io.opaa.test;

import io.opaa.auth.oidc.OidcJwtDecoderFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The one collaborator a test of the handover (#1563, ADR-0033 Entscheidung 12) cannot reach for
 * real: an identity provider's JWK set. The replacement is not a stub that accepts anything - it
 * builds the same {@link NimbusJwtDecoder} the production factory builds, only over a locally
 * generated public key instead of a fetched JWK set, with the standard validators (signature,
 * expiry, issuer byte for byte against the provider row). Only the {@code azp} check is missing,
 * because its validator is package-private to {@code io.opaa.auth.oidc}.
 *
 * <p>A top-level {@code @TestConfiguration} imported by {@link OpaaLocalAuthProviderTest} rather
 * than a nested one, so every class on that signature shares one Spring context (AGENTS.md,
 * "Spring-Testkontexte"). Stateless: the key pair is generated once and nothing a test does changes
 * it, so no reset listener is needed.
 */
@TestConfiguration
public class OidcProviderTokenTestConfiguration {

  @Bean
  public OidcProviderTokens oidcProviderTokens() {
    return new OidcProviderTokens();
  }

  @Bean
  @Primary
  OidcJwtDecoderFactory testOidcJwtDecoderFactory(OidcProviderTokens tokens) {
    return provider -> {
      NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(tokens.publicKey()).build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(provider.getIssuerUri()));
      return (JwtDecoder) decoder;
    };
  }
}
