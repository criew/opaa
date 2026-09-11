package io.opaa.auth.local;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Mints the access tokens of local accounts (ADR-0033, Entscheidung 6): HS256 under the
 * access-token key, with {@code jti} (revocable), {@code iss}, {@code sub} = the account id, {@code
 * iat}, {@code exp} after {@code opaa.auth.local.access-token-ttl}, {@code email} and {@code name}
 * - read by {@code TokenClaims} like any provider's - and {@code pcr}. No role: roles come from
 * {@code users} on every request. The encoder is built on first use so a {@code dev} start without
 * a secret is unaffected.
 */
@Service
public class LocalAccessTokenService {

  /** Boolean claim: the account must change its password before anything else is reachable. */
  public static final String PCR_CLAIM = "pcr";

  private static final String EMAIL_CLAIM = "email";
  private static final String NAME_CLAIM = "name";

  private final LocalAuthKeyService keys;
  private final LocalAuthProperties properties;
  private final Clock clock;
  private volatile JwtEncoder encoder;

  public LocalAccessTokenService(
      LocalAuthKeyService keys, LocalAuthProperties properties, Clock clock) {
    this.keys = keys;
    this.properties = properties;
    this.clock = clock;
  }

  public IssuedAccessToken issue(User user, boolean passwordChangeRequired) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(properties.accessTokenTtl());
    String jti = UUID.randomUUID().toString();
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .id(jti)
            .issuer(LocalIssuer.URN)
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(PCR_CLAIM, passwordChangeRequired);
    if (user.getEmail() != null) {
      claims.claim(EMAIL_CLAIM, user.getEmail());
    }
    if (user.getDisplayName() != null) {
      claims.claim(NAME_CLAIM, user.getDisplayName());
    }
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
    String value =
        encoder().encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    return new IssuedAccessToken(value, jti, now, expiresAt);
  }

  private JwtEncoder encoder() {
    JwtEncoder current = encoder;
    if (current == null) {
      synchronized (this) {
        current = encoder;
        if (current == null) {
          current = new NimbusJwtEncoder(new ImmutableSecret<>(keys.key(Purpose.ACCESS_TOKEN)));
          encoder = current;
        }
      }
    }
    return current;
  }

  /** A minted access token with what the response and the denylist need to know about it. */
  public record IssuedAccessToken(String value, String jti, Instant issuedAt, Instant expiresAt) {

    public long expiresInSeconds() {
      return Duration.between(issuedAt, expiresAt).toSeconds();
    }
  }
}
