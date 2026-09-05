package io.opaa.auth.oidc;

import io.opaa.auth.AuthProperties;
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
 * asks the registry for the manager that verifies the rest.
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

  @Bean
  AuthenticationManagerResolver<HttpServletRequest> oidcAuthenticationManagerResolver(
      OidcProviderRegistry registry) {
    return new JwtIssuerAuthenticationManagerResolver(registry);
  }
}
