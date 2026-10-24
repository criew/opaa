package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.AccessDeniedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * {@link RecentLoginPolicy}: only a token whose {@code auth_time} lies within the configured window
 * passes; a token without it - a refresh of the local issuer, a provider that does not send it, a
 * principal that is no JWT at all - never does.
 */
class RecentLoginPolicyTest {

  private static final Instant NOW = Instant.parse("2026-10-23T09:00:00Z");

  private final RecentLoginPolicy policy =
      new RecentLoginPolicy(
          new RecentLoginProperties(Duration.ofMinutes(5)), Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aSignInWithinTheWindowPasses() {
    assertThatCode(() -> policy.requireRecentLogin(tokenSignedInAt(NOW.minusSeconds(60))))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> policy.requireRecentLogin(tokenSignedInAt(NOW.minus(Duration.ofMinutes(5)))))
        .as("the window includes its edge")
        .doesNotThrowAnyException();
  }

  @Test
  void aSignInOlderThanTheWindowIsRefusedWithItsReason() {
    assertRefused(
        tokenSignedInAt(NOW.minus(Duration.ofMinutes(5)).minusSeconds(1)), "AUTH_TIME_TOO_OLD");
  }

  /** A refreshed local token and a provider that sends no auth_time look the same: no sign-in. */
  @Test
  void aTokenWithoutATimeOfSignInIsRefused() {
    assertRefused(token(Map.of("sub", "erika")), "AUTH_TIME_MISSING");
    assertRefused(
        token(Map.of("sub", "erika", RecentLoginPolicy.AUTH_TIME_CLAIM, "gestern")),
        "AUTH_TIME_MISSING");
  }

  @Test
  void aSignInFarInTheFutureIsNoRecentOne() {
    assertThatCode(() -> policy.requireRecentLogin(tokenSignedInAt(NOW.plusSeconds(30))))
        .as("within the clock skew of the provider")
        .doesNotThrowAnyException();
    assertRefused(tokenSignedInAt(NOW.plus(Duration.ofMinutes(10))), "AUTH_TIME_TOO_OLD");
  }

  @Test
  void aPrincipalThatIsNoJwtIsRefused() {
    assertRefused(
        new TestingAuthenticationToken("opaa_pat_x", null, List.of()), "AUTH_TIME_MISSING");
    assertRefused(null, "AUTH_TIME_MISSING");
  }

  @Test
  void readsTheTimeOfSignInInEveryShapeADecoderHandsOut() {
    long seconds = NOW.getEpochSecond();
    assertThat(RecentLoginPolicy.authTimeOf(jwt(Map.of("auth_time", seconds)))).isEqualTo(NOW);
    assertThat(RecentLoginPolicy.authTimeOf(jwt(Map.of("auth_time", (int) 1_790_000_000))))
        .isEqualTo(Instant.ofEpochSecond(1_790_000_000));
    assertThat(RecentLoginPolicy.authTimeOf(jwt(Map.of("auth_time", NOW)))).isEqualTo(NOW);
    assertThat(RecentLoginPolicy.authTimeOf(jwt(Map.of("auth_time", Date.from(NOW)))))
        .isEqualTo(NOW);
    assertThat(RecentLoginPolicy.authTimeOf(jwt(Map.of("auth_time", String.valueOf(seconds)))))
        .isEqualTo(NOW);
  }

  @Test
  void aNonPositiveWindowRefusesTheStart() {
    assertThat(new RecentLoginProperties(null).maxAge()).isEqualTo(Duration.ofMinutes(5));
    assertThatThrownBy(() -> new RecentLoginProperties(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(RecentLoginProperties.MAX_AGE_VARIABLE);
  }

  private void assertRefused(Authentication authentication, String reason) {
    assertThatThrownBy(() -> policy.requireRecentLogin(authentication))
        .isInstanceOfSatisfying(
            AccessDeniedException.class,
            denied -> {
              assertThat(denied.getCode()).isEqualTo(RecentLoginPolicy.CODE);
              assertThat(denied.getReason()).isEqualTo(reason);
              assertThat(denied.getMessage()).isEqualTo(RecentLoginPolicy.MESSAGE);
            });
  }

  private static Authentication tokenSignedInAt(Instant authTime) {
    return token(
        Map.of("sub", "erika", RecentLoginPolicy.AUTH_TIME_CLAIM, authTime.getEpochSecond()));
  }

  private static Authentication token(Map<String, Object> claims) {
    return new JwtAuthenticationToken(jwt(claims), List.of());
  }

  private static Jwt jwt(Map<String, Object> claims) {
    Map<String, Object> all = new HashMap<>(claims);
    all.putIfAbsent("sub", "erika");
    return new Jwt("t", NOW.minusSeconds(10), NOW.plusSeconds(600), Map.of("alg", "none"), all);
  }
}
