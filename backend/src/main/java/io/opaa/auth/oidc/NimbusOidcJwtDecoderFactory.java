package io.opaa.auth.oidc;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * The production {@link OidcJwtDecoderFactory} (#1329, ADR-0025 Entscheidung 1 and 3): a {@link
 * NimbusJwtDecoder} on a fixed JWK set address - the provider's override, or the {@code jwks_uri}
 * of a discovery document the {@link OidcDiscoveryClient} fetched and address-checked - with the
 * standard validators (expiry, issuer - byte for byte against the stored issuer, as OIDC requires)
 * plus {@link AuthorizedPartyValidator}. The JWK set itself is read through a client that never
 * follows redirects, so neither address can lead anywhere the policy did not see, and with the
 * discovery client's timeouts: the fetch runs on the request thread of the first token of an
 * issuer, and a provider that swallows packets must not hold that thread forever. With an override
 * no discovery happens at all, which is what makes the Compose split ({@code keycloak:8180} for the
 * backend, {@code localhost:8180} for the browser) work.
 */
public class NimbusOidcJwtDecoderFactory implements OidcJwtDecoderFactory {

  private final OidcDiscoveryClient discoveryClient;
  private final RestTemplate jwkSetClient;

  public NimbusOidcJwtDecoderFactory(OidcDiscoveryClient discoveryClient) {
    this.discoveryClient = discoveryClient;
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(OidcDiscoveryClient.CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    requestFactory.setReadTimeout(OidcDiscoveryClient.REQUEST_TIMEOUT);
    this.jwkSetClient = new RestTemplate(requestFactory);
  }

  @Override
  public JwtDecoder create(OidcProvider provider) {
    String jwkSetUri = provider.getJwkSetUri();
    if (jwkSetUri == null) {
      try {
        jwkSetUri = discoveryClient.fetchDiscovery(provider.getIssuerUri()).jwksUri();
      } catch (OidcDiscoveryClient.OidcProbeException e) {
        throw new IllegalStateException(e.getMessage(), e);
      }
    }
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withJwkSetUri(jwkSetUri).restOperations(jwkSetClient).build();
    List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
    validators.add(JwtValidators.createDefaultWithIssuer(provider.getIssuerUri()));
    validators.add(new AuthorizedPartyValidator(provider.getClientId()));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
    return decoder;
  }
}
