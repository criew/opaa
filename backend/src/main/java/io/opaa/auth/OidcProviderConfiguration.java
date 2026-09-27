package io.opaa.auth;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the provider registry and its collaborators (#1329, ADR-0025). The address policy is built
 * from the sign-in's own {@code opaa.auth.oidc.target-validation} block plus the bootstrap hosts.
 * The resolver the resource server asks per token puts the local issuer in front of this registry
 * and is wired with it in {@code io.opaa.account.LocalAuthIssuerConfiguration}.
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
}
