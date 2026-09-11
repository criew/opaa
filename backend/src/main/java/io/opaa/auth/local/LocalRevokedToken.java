package io.opaa.auth.local;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A revoked access token on the {@code jti} denylist (ADR-0033, Entscheidung 7), keyed by {@link
 * io.opaa.security.LocalAuthKeyService#jtiHash}. Kept until the token would have expired anyway;
 * the cleanup run removes it afterwards.
 */
@Entity
@Table(name = "local_revoked_tokens")
public class LocalRevokedToken {

  @Id
  @Column(name = "jti_hash", length = 64)
  private String jtiHash;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at", nullable = false)
  private Instant revokedAt;

  protected LocalRevokedToken() {}

  public LocalRevokedToken(String jtiHash, UUID userId, Instant expiresAt, Instant revokedAt) {
    this.jtiHash = Objects.requireNonNull(jtiHash, "jtiHash");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    this.revokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
  }

  public String getJtiHash() {
    return jtiHash;
  }

  public UUID getUserId() {
    return userId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }

  @Override
  public boolean equals(Object o) {
    return this == o || (o instanceof LocalRevokedToken other && jtiHash.equals(other.jtiHash));
  }

  @Override
  public int hashCode() {
    return jtiHash.hashCode();
  }
}
