package io.opaa.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.PasswordChangeReason;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.RecentLoginPolicy;
import io.opaa.auth.User;
import io.opaa.organization.Organization;
import io.opaa.security.LocalAuthKeyService;
import io.opaa.security.LocalAuthKeyService.Purpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The access tokens the local issuer mints (ADR-0033, Entscheidung 6): HS256 under the access-token
 * key, exactly the claims the ADR names - {@code jti}, {@code iss}, {@code sub} = the account id,
 * {@code iat}, {@code exp} after the configured lifetime, {@code email}, {@code name}, {@code pcr}
 * - and no role, which stays in {@code users}.
 */
class LocalAccessTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-11T10:15:30Z");

  private final LocalAuthKeyService keys =
      new LocalAuthKeyService("local-access-token-service-test-secret-0123456789");
  private final LocalAuthProperties properties =
      new LocalAuthProperties(
          "local-access-token-service-test-secret-0123456789",
          Duration.ofMinutes(15),
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
  private final LocalAccessTokenService service =
      new LocalAccessTokenService(keys, properties, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void mintsAnHs256TokenWithExactlyTheAdrsClaims() {
    User user = localUser("erika.muster@stadt.example", "Erika Muster");

    LocalAccessTokenService.IssuedAccessToken issued = service.issue(user, credentials(user));

    Jwt jwt = decode(issued.value());
    assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    // a URN, not a URL - read as the string it is, the way JwtUserClaims does
    assertThat(jwt.getClaimAsString("iss")).isEqualTo(LocalIssuer.URN);
    assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
    assertThat(jwt.getId()).isEqualTo(issued.jti());
    assertThat(UUID.fromString(jwt.getId())).isNotNull();
    assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
    assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    assertThat(jwt.getClaimAsString("email")).isEqualTo("erika.muster@stadt.example");
    assertThat(jwt.getClaimAsString("name")).isEqualTo("Erika Muster");
    assertThat(jwt.getClaimAsBoolean(LocalAccessTokenService.PCR_CLAIM)).isFalse();
    assertThat(jwt.getClaims())
        .containsEntry(LocalAccessTokenService.SESSION_GENERATION_CLAIM, 0L)
        .doesNotContainKeys("role", "roles", "azp", "aud");
    assertThat(issued.issuedAt()).isEqualTo(NOW);
    assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    assertThat(issued.expiresInSeconds()).isEqualTo(900L);
  }

  @Test
  void carriesTheForcedPasswordChangeAsPcr() {
    User user = localUser("a@stadt.example", "A");
    LocalCredentials row = credentials(user);
    row.requirePasswordChange(PasswordChangeReason.INITIAL, NOW);

    Jwt jwt = decode(service.issue(user, row).value());

    assertThat(jwt.getClaimAsBoolean(LocalAccessTokenService.PCR_CLAIM)).isTrue();
  }

  @Test
  void carriesTheSessionGenerationOfTheAccount() {
    User user = localUser("a@stadt.example", "A");
    LocalCredentials row = credentials(user);
    row.endAllSessions(NOW);
    row.endAllSessions(NOW);

    Jwt jwt = decode(service.issue(user, row).value());

    assertThat(jwt.getClaims()).containsEntry(LocalAccessTokenService.SESSION_GENERATION_CLAIM, 2L);
  }

  @Test
  void everyTokenGetsItsOwnJti() {
    User user = localUser("a@stadt.example", "A");

    assertThat(service.issue(user, credentials(user)).jti())
        .isNotEqualTo(service.issue(user, credentials(user)).jti());
  }

  /** A refresh continues a session without a password: no time of sign-in to show for. */
  @Test
  void aTokenWithoutAVerifiedPasswordNamesNoSignIn() {
    User user = localUser("a@stadt.example", "A");

    Jwt jwt = decode(service.issue(user, credentials(user)).value());

    assertThat(jwt.getClaims()).doesNotContainKey(RecentLoginPolicy.AUTH_TIME_CLAIM);
  }

  @Test
  void aTokenAfterAVerifiedPasswordNamesItsIssuanceAsTheSignIn() {
    User user = localUser("a@stadt.example", "A");

    Jwt jwt = decode(service.issueForSignIn(user, credentials(user)).value());

    assertThat(jwt.getClaims())
        .containsEntry(RecentLoginPolicy.AUTH_TIME_CLAIM, NOW.getEpochSecond());
    assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
  }

  private static LocalCredentials credentials(User user) {
    return new LocalCredentials(user.getId(), "Test", NOW);
  }

  private Jwt decode(String token) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(keys.key(Purpose.ACCESS_TOKEN))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    // only the signature and structure matter here - the fixed clock is in the past for the
    // default timestamp validator
    decoder.setJwtValidator(
        jwt -> org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success());
    return decoder.decode(token);
  }

  private static User localUser(String email, String name) {
    UUID id = UUID.randomUUID();
    User user = new User(id.toString(), LocalIssuer.URN, email, name);
    user.setOrganizationId(Organization.DEFAULT_ID);
    return user;
  }
}
