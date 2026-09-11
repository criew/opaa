package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalAuthProperties}: the ADR-0033 defaults when nothing is configured, and the upper
 * bounds (refresh idle limit at most 30 days, absolute session limit at most 90 days) and orderings
 * the compact constructor refuses - naming the environment variable in every message.
 */
class LocalAuthPropertiesTest {

  @Test
  void fillsInTheAdrDefaults() {
    LocalAuthProperties properties =
        new LocalAuthProperties(null, null, null, null, null, null, null);

    assertThat(properties.jwtSecret()).isEmpty();
    assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
    assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
    assertThat(properties.sessionMaxLifetime()).isEqualTo(Duration.ofDays(30));
    assertThat(properties.adminRefreshTokenTtl()).isEqualTo(Duration.ofHours(4));
    assertThat(properties.adminSessionMaxLifetime()).isEqualTo(Duration.ofHours(12));
    assertThat(properties.cookieSecure()).isTrue();
  }

  @Test
  void trimsTheSecret() {
    assertThat(properties("  abc  ", null, null, null, null, null).jwtSecret()).isEqualTo("abc");
  }

  @Test
  void refusesARefreshIdleLimitAboveThirtyDays() {
    assertThatThrownBy(() -> properties(null, null, Duration.ofDays(31), null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OPAA_AUTH_LOCAL_REFRESH_TOKEN_TTL")
        .hasMessageContaining("30");
  }

  @Test
  void refusesAnAbsoluteSessionLimitAboveNinetyDays() {
    assertThatThrownBy(() -> properties(null, null, null, Duration.ofDays(91), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OPAA_AUTH_LOCAL_SESSION_MAX_LIFETIME")
        .hasMessageContaining("90");
  }

  @Test
  void refusesAnIdleLimitLongerThanTheAbsoluteLimit() {
    assertThatThrownBy(
            () -> properties(null, null, Duration.ofDays(20), Duration.ofDays(10), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OPAA_AUTH_LOCAL_REFRESH_TOKEN_TTL")
        .hasMessageContaining("OPAA_AUTH_LOCAL_SESSION_MAX_LIFETIME");
    assertThatThrownBy(
            () -> properties(null, null, null, null, Duration.ofHours(13), Duration.ofHours(12)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OPAA_AUTH_LOCAL_ADMIN_REFRESH_TOKEN_TTL")
        .hasMessageContaining("OPAA_AUTH_LOCAL_ADMIN_SESSION_MAX_LIFETIME");
  }

  @Test
  void refusesAdminLimitsLongerThanTheRegularOnes() {
    assertThatThrownBy(
            () -> properties(null, null, null, null, Duration.ofDays(8), Duration.ofDays(9)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OPAA_AUTH_LOCAL_ADMIN_REFRESH_TOKEN_TTL");
  }

  @Test
  void refusesNonPositiveOrOversizedAccessTokenLifetimes() {
    assertThatThrownBy(() -> properties(null, Duration.ZERO, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OPAA_AUTH_LOCAL_ACCESS_TOKEN_TTL");
    assertThatThrownBy(() -> properties(null, Duration.ofHours(5), null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("OPAA_AUTH_LOCAL_ACCESS_TOKEN_TTL")
        .hasMessageContaining("OPAA_AUTH_LOCAL_ADMIN_REFRESH_TOKEN_TTL");
  }

  @Test
  void acceptsTheUpperBoundsThemselves() {
    LocalAuthProperties properties =
        properties(null, Duration.ofHours(1), Duration.ofDays(30), Duration.ofDays(90), null, null);

    assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(30));
    assertThat(properties.sessionMaxLifetime()).isEqualTo(Duration.ofDays(90));
  }

  private static LocalAuthProperties properties(
      String secret,
      Duration access,
      Duration refresh,
      Duration session,
      Duration adminRefresh,
      Duration adminSession) {
    return new LocalAuthProperties(
        secret, access, refresh, session, adminRefresh, adminSession, null);
  }
}
