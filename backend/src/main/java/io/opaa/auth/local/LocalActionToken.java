package io.opaa.auth.local;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single-use link (ADR-0033, Entscheidungen 3, 11 and 12): invitation, password reset, address
 * confirmation or handover. Only the HMAC of the raw token is stored; redemption is the atomic
 * {@link LocalActionTokenRepository#markConsumed}, never a read-then-write on this entity.
 */
@Entity
@Table(name = "local_action_tokens")
public class LocalActionToken {

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "purpose", nullable = false, length = 32)
  private ActionTokenPurpose purpose;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  /** Set exactly for {@link ActionTokenPurpose#HANDOVER}: the provider the administration chose. */
  @Column(name = "provider_id")
  private UUID providerId;

  /** Set exactly for {@link ActionTokenPurpose#HANDOVER}: the reason, for the person to read. */
  @Column(name = "reason", length = 200)
  private String reason;

  protected LocalActionToken() {}

  public LocalActionToken(
      UUID userId,
      ActionTokenPurpose purpose,
      String tokenHash,
      Instant createdAt,
      Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.userId = Objects.requireNonNull(userId, "userId");
    this.purpose = Objects.requireNonNull(purpose, "purpose");
    this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /**
   * A handover link (ADR-0033, Entscheidung 12): provider and reason belong to the code, not to the
   * account - the account stops being a local one the moment the code is redeemed.
   */
  public LocalActionToken(
      UUID userId,
      String tokenHash,
      Instant createdAt,
      Instant expiresAt,
      UUID providerId,
      String reason) {
    this(userId, ActionTokenPurpose.HANDOVER, tokenHash, createdAt, expiresAt);
    this.providerId = Objects.requireNonNull(providerId, "providerId");
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  /** Not yet consumed and not yet expired. */
  public boolean isRedeemable(Instant now) {
    return consumedAt == null && expiresAt.isAfter(now);
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public ActionTokenPurpose getPurpose() {
    return purpose;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public UUID getProviderId() {
    return providerId;
  }

  public String getReason() {
    return reason;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof LocalActionToken other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
