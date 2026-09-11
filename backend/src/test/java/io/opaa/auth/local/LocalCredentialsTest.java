package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.PasswordChangeReason;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalCredentials}: the account state is derived, never stored (ADR-0033 Entscheidung 3) -
 * a lock outranks everything, then expiry, then the invitation that is not yet complete, and only a
 * complete, unlocked, unexpired account is {@code ACTIVE} and therefore able to sign in.
 */
class LocalCredentialsTest {

  private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");

  @Test
  void aFreshAccountIsInvitedUntilItHasAPasswordAndAVerifiedAddress() {
    LocalCredentials credentials = credentials();

    assertThat(credentials.state(NOW)).isEqualTo(LocalAccountState.INVITED);
    credentials.setPasswordHash("{bcrypt}x", NOW);
    assertThat(credentials.state(NOW)).isEqualTo(LocalAccountState.INVITED);
    credentials.markEmailVerified(NOW);
    assertThat(credentials.state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(credentials.isLoginCapable(NOW)).isTrue();
  }

  @Test
  void expiryMakesTheAccountExpiredFromTheMomentItPasses() {
    LocalCredentials credentials = activeCredentials();
    credentials.setExpiresAt(NOW.plus(Duration.ofDays(1)), NOW);

    assertThat(credentials.state(NOW)).isEqualTo(LocalAccountState.ACTIVE);
    assertThat(credentials.state(NOW.plus(Duration.ofDays(1))))
        .isEqualTo(LocalAccountState.EXPIRED);
    assertThat(credentials.isLoginCapable(NOW.plus(Duration.ofDays(2)))).isFalse();
  }

  @Test
  void aLockOutranksExpiryAndInvitation() {
    LocalCredentials credentials = credentials();
    credentials.setExpiresAt(NOW.minusSeconds(1), NOW);
    credentials.lock(LockReason.ADMIN, NOW, null);

    assertThat(credentials.state(NOW)).isEqualTo(LocalAccountState.LOCKED);
    assertThat(credentials.getLockedAt()).isEqualTo(NOW);
    assertThat(credentials.getLockedReason()).isEqualTo(LockReason.ADMIN);
  }

  @Test
  void aFailedLoginLockEndsWithItsLockoutAndUnlockResetsTheCounter() {
    LocalCredentials credentials = activeCredentials();
    credentials.lock(LockReason.FAILED_LOGINS, NOW, NOW.plus(Duration.ofMinutes(15)));

    assertThat(credentials.state(NOW)).isEqualTo(LocalAccountState.LOCKED);
    assertThat(credentials.getLockoutUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    // regression guard: a failed-login lock ends on its own, it is never permanent
    assertThat(credentials.state(NOW.plus(Duration.ofMinutes(16))))
        .isEqualTo(LocalAccountState.ACTIVE);
    assertThat(credentials.getLockedReason()).isEqualTo(LockReason.FAILED_LOGINS);

    credentials.unlock(NOW.plus(Duration.ofMinutes(1)));

    assertThat(credentials.state(NOW.plus(Duration.ofMinutes(1))))
        .isEqualTo(LocalAccountState.ACTIVE);
    assertThat(credentials.getLockedAt()).isNull();
    assertThat(credentials.getLockedReason()).isNull();
    assertThat(credentials.getLockoutUntil()).isNull();
    assertThat(credentials.getFailedLoginAttempts()).isZero();
  }

  @Test
  void aRecordedLockoutIsAFailedLoginLockWithItsReason() {
    LocalCredentials credentials = activeCredentials();
    credentials.recordLockoutUntil(NOW.plus(Duration.ofMinutes(15)), NOW);

    assertThat(credentials.state(NOW)).isEqualTo(LocalAccountState.LOCKED);
    assertThat(credentials.getLockedAt()).isEqualTo(NOW);
    assertThat(credentials.getLockedReason()).isEqualTo(LockReason.FAILED_LOGINS);
    assertThat(credentials.state(NOW.plus(Duration.ofMinutes(16))))
        .isEqualTo(LocalAccountState.ACTIVE);
  }

  @Test
  void anAdministrativeLockNeverEndsOnItsOwn() {
    LocalCredentials credentials = activeCredentials();
    credentials.lock(LockReason.INACTIVITY, NOW, null);

    assertThat(credentials.state(NOW.plus(Duration.ofDays(400))))
        .isEqualTo(LocalAccountState.LOCKED);
  }

  @Test
  void lockoutUntilBelongsToFailedLoginLocksOnly() {
    LocalCredentials credentials = activeCredentials();

    assertThatThrownBy(() -> credentials.lock(LockReason.FAILED_LOGINS, NOW, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> credentials.lock(LockReason.ADMIN, NOW, NOW.plus(Duration.ofMinutes(1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void passwordChangeRequirementAlwaysCarriesItsReason() {
    LocalCredentials credentials = activeCredentials();

    credentials.requirePasswordChange(PasswordChangeReason.ADMIN_RESET, NOW);
    assertThat(credentials.isPasswordChangeRequired()).isTrue();
    assertThat(credentials.getPasswordChangeReason()).isEqualTo(PasswordChangeReason.ADMIN_RESET);

    credentials.clearPasswordChangeRequirement(NOW);
    assertThat(credentials.isPasswordChangeRequired()).isFalse();
    assertThat(credentials.getPasswordChangeReason()).isNull();

    assertThatThrownBy(() -> credentials.requirePasswordChange(null, NOW))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void invalidatingSessionsRecordsTheCutoff() {
    LocalCredentials credentials = activeCredentials();
    Instant later = NOW.plus(Duration.ofHours(1));

    credentials.invalidateSessionsIssuedBefore(NOW, later);

    assertThat(credentials.getPasswordInvalidatedBefore()).isEqualTo(NOW);
    assertThat(credentials.getUpdatedAt()).isEqualTo(later);
  }

  @Test
  void theCreationReasonIsMandatoryAndBounded() {
    assertThatThrownBy(() -> new LocalCredentials(UUID.randomUUID(), "  ", NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LocalCredentials(UUID.randomUUID(), "x".repeat(201), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("200");
    assertThat(new LocalCredentials(UUID.randomUUID(), "  Vertretung  ", NOW).getCreatedReason())
        .isEqualTo("Vertretung");
  }

  private static LocalCredentials credentials() {
    return new LocalCredentials(UUID.randomUUID(), "Sachbearbeitung", NOW);
  }

  private static LocalCredentials activeCredentials() {
    LocalCredentials credentials = credentials();
    credentials.setPasswordHash("{bcrypt}x", NOW);
    credentials.markEmailVerified(NOW);
    return credentials;
  }
}
