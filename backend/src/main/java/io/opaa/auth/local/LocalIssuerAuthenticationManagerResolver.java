package io.opaa.auth.local;

import io.opaa.auth.LocalIssuer;
import io.opaa.auth.oidc.OidcProviderRegistry;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

/**
 * Puts the local issuer in front of the provider registry (ADR-0033, Entscheidung 8): a token with
 * {@code iss = urn:opaa:local} is verified by the fixed {@link LocalAccessTokenDecoder}, whether or
 * not a {@code LOCAL} provider row exists or is enabled - the switch acts inside the validator, so
 * a local {@code SYSTEM_ADMIN} stays verifiable while the management is off. Every other issuer is
 * the registry's business as before.
 */
public class LocalIssuerAuthenticationManagerResolver
    implements AuthenticationManagerResolver<String> {

  private final OidcProviderRegistry registry;
  private final AuthenticationManager localAuthenticationManager;

  public LocalIssuerAuthenticationManagerResolver(
      OidcProviderRegistry registry, JwtDecoder localAccessTokenDecoder) {
    this.registry = registry;
    JwtAuthenticationProvider provider = new JwtAuthenticationProvider(localAccessTokenDecoder);
    this.localAuthenticationManager = provider::authenticate;
  }

  @Override
  public AuthenticationManager resolve(String issuer) {
    if (LocalIssuer.URN.equals(issuer)) {
      return localAuthenticationManager;
    }
    return registry.resolve(issuer);
  }
}
