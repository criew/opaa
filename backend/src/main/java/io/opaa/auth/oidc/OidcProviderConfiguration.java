package io.opaa.auth.oidc;

import io.opaa.auth.AuthProperties;
import io.opaa.auth.local.LocalAccessTokenDecoder;
import io.opaa.auth.local.LocalIssuerAuthenticationManagerResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;

/**
 * Wires the provider registry and its collaborators (#1329, ADR-0025). The address policy is built
 * from the sign-in's own {@code opaa.auth.oidc.target-validation} block plus the bootstrap hosts.
 * The resolver bean is what {@code OidcSecurityConfig} hands to the resource server: {@link
 * JwtIssuerAuthenticationManagerResolver} reads the token's {@code iss} (without verifying it) and
 * asks the registry - behind the local issuer's own decoder (ADR-0033) - for the manager that
 * verifies the rest.
 */
@Configuration
public class OidcProviderConfiguration {

  @Bean
  OidcAddressPolicy oidcAddressPolicy(AuthProperties authProperties) {
    return OidcAddressPolicy.fromProperties(authProperties.oidc());
  }

  @Bean
  OidcDiscoveryClient oidcDiscoveryClient(OidcAddressPolicy addressPolicy) {
    return new OidcDiscoveryClient(addressPolicy);
  }

  @Bean
  OidcJwtDecoderFactory oidcJwtDecoderFactory(OidcDiscoveryClient discoveryClient) {
    return new NimbusOidcJwtDecoderFactory(discoveryClient);
  }

  @Bean
  OidcProviderRegistry oidcProviderRegistry(
      OidcProviderRepository repository,
      OidcJwtDecoderFactory decoderFactory,
      OidcAddressPolicy addressPolicy,
      Clock clock) {
    return new OidcProviderRegistry(repository, decoderFactory, addressPolicy, clock);
  }

  @Bean
  OidcProviderConnectionTester oidcProviderConnectionTester(OidcDiscoveryClient discoveryClient) {
    return new OidcProviderConnectionTester(discoveryClient);
  }

  /**
   * ADR-0033, Entscheidung 8: the local issuer sits in front of the registry with its fixed HS256
   * decoder, independent of any provider row and of the management switch, so a local {@code
   * SYSTEM_ADMIN} stays verifiable while the management is off.
   */
  @Bean
  AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver(
      OidcProviderRegistry registry, LocalAccessTokenDecoder localAccessTokenDecoder) {
    return new JwtIssuerAuthenticationManagerResolver(
        new LocalIssuerAuthenticationManagerResolver(registry, localAccessTokenDecoder));
  }
}
