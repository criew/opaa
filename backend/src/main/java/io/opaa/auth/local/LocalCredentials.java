package io.opaa.auth.local;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The local half of a local account (ADR-0033, Entscheidung 3): password hash, forced-change and
 * lock state, expiry, address confirmation and the mandatory creation reason, keyed by {@code
 * users.id}. A row exists exactly when the user's issuer is {@link LocalAuthProperties#ISSUER} - an
 * invariant of the single write path, not of the schema. The account state is derived by {@link
 * #state}, never stored; the failed-login counter is never exposed and returns to zero on every
 * successful sign-in, reset and unlock.
 */
@Entity
@Table(name = "local_credentials")
public class LocalCredentials {

  public static final int CREATED_REASON_MAX_LENGTH = 200;

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "password_hash", length = 255)
  private String passwordHash;

  @Column(name = "password_change_required", nullable = false)
  private boolean passwordChangeRequired;

  @Enumerated(EnumType.STRING)
  @Column(name = "password_change_reason", length = 32)
  private PasswordChangeReason passwordChangeReason;

  @Column(name = "password_invalidated_before")
  private Instant passwordInvalidatedBefore;

  @Column(name = "locked_at")
  private Instant lockedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "locked_reason", length = 32)
  private LockReason lockedReason;

  @Column(name = "failed_login_attempts", nullable = false)
  private int failedLoginAttempts;

  @Column(name = "lockout_until")
  private Instant lockoutUntil;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  @Column(name = "created_reason", nullable = false, length = CREATED_REASON_MAX_LENGTH)
  private String createdReason;

  @Column(name = "is_bootstrap", nullable = false)
  private boolean bootstrap;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected LocalCredentials() {}

  /** A new, invited account: no password, no confirmed address. */
  public LocalCredentials(UUID userId, String createdReason, Instant now) {
    this.userId = Objects.requireNonNull(userId, "userId");
    this.createdReason = requireCreatedReason(createdReason);
    this.createdAt = Objects.requireNonNull(now, "now");
    this.updatedAt = now;
  }

  /**
   * The derived state: a lock outranks everything (a locked invitation must not be redeemable),
   * then expiry, then an incomplete invitation; only what remains is {@link
   * LocalAccountState#ACTIVE}.
   */
  public LocalAccountState state(Instant now) {
    if (lockedAt != null || (lockoutUntil != null && lockoutUntil.isAfter(now))) {
      return LocalAccountState.LOCKED;
    }
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      return LocalAccountState.EXPIRED;
    }
    if (passwordHash == null || emailVerifiedAt == null) {
      return LocalAccountState.INVITED;
    }
    return LocalAccountState.ACTIVE;
  }

  public boolean isLoginCapable(Instant now) {
    return state(now) == LocalAccountState.ACTIVE;
  }

  /** Stores an already encoded hash (never a plain password). */
  public void setPasswordHash(String passwordHash, Instant now) {
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
    touch(now);
  }

  public void requirePasswordChange(PasswordChangeReason reason, Instant now) {
    this.passwordChangeReason = Objects.requireNonNull(reason, "reason");
    this.passwordChangeRequired = true;
    touch(now);
  }

  public void clearPasswordChangeRequirement(Instant now) {
    this.passwordChangeRequired = false;
    this.passwordChangeReason = null;
    touch(now);
  }

  /** Every token issued before {@code cutoff} becomes invalid - the mass revocation. */
  public void invalidateSessionsIssuedBefore(Instant cutoff) {
    this.passwordInvalidatedBefore = Objects.requireNonNull(cutoff, "cutoff");
    touch(cutoff);
  }

  /** Locks the account; {@code lockoutUntil} is set only for {@link LockReason#FAILED_LOGINS}. */
  public void lock(LockReason reason, Instant lockedAt, Instant lockoutUntil) {
    this.lockedReason = Objects.requireNonNull(reason, "reason");
    this.lockedAt = Objects.requireNonNull(lockedAt, "lockedAt");
    this.lockoutUntil = lockoutUntil;
    touch(lockedAt);
  }

  /** Lifts any lock and the failed-login state at once. */
  public void unlock(Instant now) {
    this.lockedAt = null;
    this.lockedReason = null;
    this.lockoutUntil = null;
    this.failedLoginAttempts = 0;
    touch(now);
  }

  /** A temporary lockout after failed sign-ins without an administrative lock. */
  public void recordLockoutUntil(Instant lockoutUntil, Instant now) {
    this.lockoutUntil = lockoutUntil;
    touch(now);
  }

  public void resetFailedLoginAttempts(Instant now) {
    this.failedLoginAttempts = 0;
    touch(now);
  }

  public void setExpiresAt(Instant expiresAt, Instant now) {
    this.expiresAt = expiresAt;
    touch(now);
  }

  public void markEmailVerified(Instant now) {
    this.emailVerifiedAt = Objects.requireNonNull(now, "now");
    touch(now);
  }

  public void markBootstrap() {
    this.bootstrap = true;
  }

  public void setCreatedReason(String createdReason, Instant now) {
    this.createdReason = requireCreatedReason(createdReason);
    touch(now);
  }

  public UUID getUserId() {
    return userId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean isPasswordChangeRequired() {
    return passwordChangeRequired;
  }

  public PasswordChangeReason getPasswordChangeReason() {
    return passwordChangeReason;
  }

  public Instant getPasswordInvalidatedBefore() {
    return passwordInvalidatedBefore;
  }

  public Instant getLockedAt() {
    return lockedAt;
  }

  public LockReason getLockedReason() {
    return lockedReason;
  }

  public int getFailedLoginAttempts() {
    return failedLoginAttempts;
  }

  public Instant getLockoutUntil() {
    return lockoutUntil;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getEmailVerifiedAt() {
    return emailVerifiedAt;
  }

  public String getCreatedReason() {
    return createdReason;
  }

  public boolean isBootstrap() {
    return bootstrap;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  private void touch(Instant now) {
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  private static String requireCreatedReason(String createdReason) {
    String trimmed = createdReason == null ? "" : createdReason.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("createdReason must not be blank");
    }
    if (trimmed.length() > CREATED_REASON_MAX_LENGTH) {
      throw new IllegalArgumentException(
          "createdReason must not exceed " + CREATED_REASON_MAX_LENGTH + " characters");
    }
    return trimmed;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof LocalCredentials other && userId.equals(other.userId));
  }

  @Override
  public int hashCode() {
    return userId.hashCode();
  }
}
