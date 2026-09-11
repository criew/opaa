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
 * One refresh token of a local session (ADR-0033, Entscheidung 7). Only the HMAC lookup hash of the
 * cookie value is stored. Tokens form a family: rotation revokes the presented token ({@link
 * RevocationReason#ROTATED}) and points it at its successor - atomically, through {@link
 * LocalRefreshTokenRepository#rotateIfActive}, never by a read-then-write on this entity; {@link
 * #getExpiresAt()} is the idle limit, {@link #getFamilyExpiresAt()} the absolute end every
 * successor inherits unchanged.
 */
@Entity
@Table(name = "local_refresh_tokens")
public class LocalRefreshToken {

  @Id private UUID id;

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "token_lookup_hash", nullable = false, length = 64)
  private String tokenLookupHash;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "family_expires_at", nullable = false)
  private Instant familyExpiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "revocation_reason", length = 32)
  private RevocationReason revocationReason;

  @Column(name = "rotated_to_id")
  private UUID rotatedToId;

  protected LocalRefreshToken() {}

  public LocalRefreshToken(
      UUID familyId,
      UUID userId,
      String tokenLookupHash,
      Instant issuedAt,
      Instant expiresAt,
      Instant familyExpiresAt) {
    this.id = UUID.randomUUID();
    this.familyId = Objects.requireNonNull(familyId, "familyId");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.tokenLookupHash = Objects.requireNonNull(tokenLookupHash, "tokenLookupHash");
    this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    this.familyExpiresAt = Objects.requireNonNull(familyExpiresAt, "familyExpiresAt");
  }

  /** Unrevoked, within the idle limit and within the family's absolute end. */
  public boolean isActive(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now) && familyExpiresAt.isAfter(now);
  }

  public void revoke(RevocationReason reason, Instant now) {
    this.revocationReason = Objects.requireNonNull(reason, "reason");
    this.revokedAt = Objects.requireNonNull(now, "now");
  }

  public UUID getId() {
    return id;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getTokenLookupHash() {
    return tokenLookupHash;
  }

  public Instant getIssuedAt() {
    return issuedAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getFamilyExpiresAt() {
    return familyExpiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public RevocationReason getRevocationReason() {
    return revocationReason;
  }

  public UUID getRotatedToId() {
    return rotatedToId;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof LocalRefreshToken other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
