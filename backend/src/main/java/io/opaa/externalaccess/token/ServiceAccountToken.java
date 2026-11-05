package io.opaa.externalaccess.token;

import io.opaa.api.types.ExternalAccessTokenStatus;
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
 * One token of a service account (ADR-0035, Entscheidung 7): the value shape and lookup hash of a
 * personal token, but no library selection - its view is whatever is consented to its account at
 * the moment of each call - and no day of last use, because the use of a token is never recorded.
 *
 * <p>Every end of the token - revocation, deactivation of the account or the expiry the daily run
 * records - sets {@code lapseRecordedAt} with it, so the Loeschfrist of the row has a start.
 */
@Entity
@Table(name = "service_account_tokens")
public class ServiceAccountToken implements ListedAccessToken {

  @Id private UUID id;

  @Column(name = "service_account_id", nullable = false, updatable = false)
  private UUID serviceAccountId;

  @Column(name = "name", nullable = false, length = 120, updatable = false)
  private String name;

  @Column(name = "token_prefix", nullable = false, length = 32, updatable = false)
  private String tokenPrefix;

  @Column(name = "token_lookup_hash", nullable = false, length = 64, updatable = false)
  private String tokenLookupHash;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "revocation_reason", length = 32)
  private ExternalAccessTokenRevocationReason revocationReason;

  @Column(name = "lapse_recorded_at")
  private Instant lapseRecordedAt;

  protected ServiceAccountToken() {}

  public ServiceAccountToken(
      UUID serviceAccountId,
      String name,
      String tokenPrefix,
      String tokenLookupHash,
      Instant createdAt,
      Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.serviceAccountId = Objects.requireNonNull(serviceAccountId, "serviceAccountId");
    this.name = Objects.requireNonNull(name, "name");
    this.tokenPrefix = Objects.requireNonNull(tokenPrefix, "tokenPrefix");
    this.tokenLookupHash = Objects.requireNonNull(tokenLookupHash, "tokenLookupHash");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /** Neither revoked nor past its expiry. */
  public boolean isActive(Instant now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }

  /** The state the lists show; derived, never stored. */
  public ExternalAccessTokenStatus status(Instant now) {
    if (revocationReason != null) {
      return revocationReason.status();
    }
    return expiresAt.isAfter(now)
        ? ExternalAccessTokenStatus.ACTIVE
        : ExternalAccessTokenStatus.EXPIRED;
  }

  /**
   * Ends the token and starts its Loeschfrist; {@code false} when it had already ended, which keeps
   * the first reason and moment.
   */
  public boolean revoke(ExternalAccessTokenRevocationReason reason, Instant now) {
    if (revokedAt != null) {
      return false;
    }
    this.revocationReason = Objects.requireNonNull(reason, "reason");
    this.revokedAt = Objects.requireNonNull(now, "now");
    this.lapseRecordedAt = now;
    return true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getServiceAccountId() {
    return serviceAccountId;
  }

  public String getName() {
    return name;
  }

  public String getTokenPrefix() {
    return tokenPrefix;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  public ExternalAccessTokenRevocationReason getRevocationReason() {
    return revocationReason;
  }

  public Instant getLapseRecordedAt() {
    return lapseRecordedAt;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof ServiceAccountToken other && id.equals(other.id));
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
