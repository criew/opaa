package io.opaa.auth.local;

import io.opaa.auth.LocalIssuer;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * The {@link JwtDecoder} of the local issuer (ADR-0033, Entscheidung 8): HS256 under the
 * access-token key with the standard issuer and timestamp validators, then the {@link
 * LocalTokenValidator}. A rejection is thrown as {@link BadJwtException} whose message is the
 * marker - {@code JwtAuthenticationProvider} turns exactly that message into the {@code
 * error_description} of {@code WWW-Authenticate}; the Nimbus validators' own wording would bury it
 * behind a generic prefix, which is why the revocation check is a wrapper and not another
 * validator. The Nimbus decoder is built on first use so a {@code dev} start without a secret is
 * unaffected.
 */
@Component
public class LocalAccessTokenDecoder implements JwtDecoder {

  private final LocalAuthKeyService keys;
  private final LocalTokenValidator validator;
  private volatile NimbusJwtDecoder signatureDecoder;

  public LocalAccessTokenDecoder(LocalAuthKeyService keys, LocalTokenValidator validator) {
    this.keys = keys;
    this.validator = validator;
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    Jwt jwt = signatureDecoder().decode(token);
    validator
        .rejectionFor(jwt)
        .ifPresent(
            rejection -> {
              throw new BadJwtException(rejection.errorDescription());
            });
    return jwt;
  }

  private NimbusJwtDecoder signatureDecoder() {
    NimbusJwtDecoder decoder = signatureDecoder;
    if (decoder == null) {
      synchronized (this) {
        decoder = signatureDecoder;
        if (decoder == null) {
          decoder =
              NimbusJwtDecoder.withSecretKey(keys.key(Purpose.ACCESS_TOKEN))
                  .macAlgorithm(MacAlgorithm.HS256)
                  .build();
          decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(LocalIssuer.URN));
          signatureDecoder = decoder;
        }
      }
    }
    return decoder;
  }
}
