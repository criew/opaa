package io.opaa.test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * One RSA key pair for the whole suite that needs provider tokens (#1563): the tests mint tokens
 * with the private half, and the decoder {@link OidcProviderTokenTestConfiguration} hands the
 * registry verifies them with the public one - real signatures, real issuer and expiry checks,
 * without an identity provider on the network.
 */
public final class OidcProviderTokens {

  private final RSAKey key;

  OidcProviderTokens() {
    try {
      this.key = new RSAKeyGenerator(2048).keyID("opaa-test").generate();
    } catch (JOSEException e) {
      throw new IllegalStateException("test key pair", e);
    }
  }

  public RSAPublicKey publicKey() {
    try {
      return key.toRSAPublicKey();
    } catch (JOSEException e) {
      throw new IllegalStateException("test public key", e);
    }
  }

  /** A token valid for five minutes. */
  public String token(String issuer, String subject) {
    return token(issuer, subject, Duration.ofMinutes(5));
  }

  /** A token whose validity ends {@code ttl} from now; a negative {@code ttl} is expired. */
  public String token(String issuer, String subject, Duration ttl) {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .subject(subject)
            .claim("email", subject + "@idp.test.example")
            .claim("name", "Anbieterkonto")
            .issueTime(Date.from(now.minusSeconds(30)))
            .expirationTime(Date.from(now.plus(ttl)))
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    try {
      jwt.sign(new RSASSASigner(key));
    } catch (JOSEException e) {
      throw new IllegalStateException("signing the test token", e);
    }
    return jwt.serialize();
  }
}
