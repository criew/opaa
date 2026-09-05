package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@link AuthorizedPartyValidator} (ADR-0025, Entscheidung 1): the provider's own client passes, a
 * token without {@code azp} passes, a token of another client passes only when the provider
 * declared it for this client through {@code aud} - the service client of the demo seed is that
 * case - and is refused otherwise.
 */
class AuthorizedPartyValidatorTest {

  private final AuthorizedPartyValidator validator = new AuthorizedPartyValidator("opaa-frontend");

  private static Jwt token(String azp, List<String> audience) {
    Jwt.Builder builder =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("alice")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60));
    if (azp != null) {
      builder.claim("azp", azp);
    }
    if (audience != null) {
      builder.audience(audience);
    }
    return builder.build();
  }

  @Test
  void theProvidersOwnClientPassesAndSoDoesATokenWithoutAzp() {
    assertThat(validator.validate(token("opaa-frontend", null)).hasErrors()).isFalse();
    assertThat(validator.validate(token(null, null)).hasErrors()).isFalse();
  }

  @Test
  void anotherClientsTokenIsRefusedUnlessItsAudienceNamesThisClient() {
    assertThat(validator.validate(token("other-app", null)).hasErrors()).isTrue();
    assertThat(validator.validate(token("other-app", List.of("account"))).hasErrors()).isTrue();
    // the seed client's tokens, declared for the application through an audience mapper
    assertThat(
            validator.validate(token("opaa-seed", List.of("opaa-frontend", "account"))).hasErrors())
        .isFalse();
  }
}
