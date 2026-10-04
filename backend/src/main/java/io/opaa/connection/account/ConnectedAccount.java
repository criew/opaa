package io.opaa.connection.account;

import io.opaa.api.types.ConnectedAccountState;
import io.opaa.api.types.ConnectionEndCause;
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
 * A person's connected account on a profile. Its secret lies in the token store; the account name
 * at the provider is held only as ciphertext, for the person alone. {@link #toString} shows
 * neither.
 */
@Entity
@Table(name = "connected_accounts")
class ConnectedAccount {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "profile_id", nullable = false)
  private UUID profileId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private ConnectedAccountState state;

  @Enumerated(EnumType.STRING)
  @Column(name = "ended_cause", length = 30)
  private ConnectionEndCause endedCause;

  @Column(name = "account_label_ciphertext")
  private String accountLabelCiphertext;

  @Column(name = "connected_at", nullable = false)
  private Instant connectedAt;

  @Column(name = "reconnected_at")
  private Instant reconnectedAt;

  @Column(name = "expired_at")
  private Instant expiredAt;

  /** Read in queries only; written by {@code ConnectedAccountRepository#markUsed} alone. */
  @Column(name = "last_used_at", insertable = false, updatable = false)
  private Instant lastUsedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  protected ConnectedAccount() {}

  ConnectedAccount(UUID organizationId, UUID userId, UUID profileId, Instant now) {
    this.id = UUID.randomUUID();
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.profileId = Objects.requireNonNull(profileId, "profileId");
    this.state = ConnectedAccountState.CONNECTED;
    this.connectedAt = now;
  }

  /** Connected (again) with a new secret; {@code labelCiphertext} replaces the account name. */
  void connected(String labelCiphertext, Instant now, boolean again) {
    this.state = ConnectedAccountState.CONNECTED;
    this.endedCause = null;
    this.expiredAt = null;
    this.accountLabelCiphertext = labelCiphertext;
    if (again) {
      this.reconnectedAt = now;
    }
  }

  /** The provider rejected the secret or it ran out. */
  void expired(ConnectionEndCause cause, Instant now) {
    this.state = ConnectedAccountState.EXPIRED;
    this.endedCause = cause;
    this.expiredAt = now;
  }

  /** Disconnected for {@code cause}; kept only while a private library runs on it. */
  void disconnected(ConnectionEndCause cause) {
    this.state = ConnectedAccountState.DISCONNECTED;
    this.endedCause = cause;
    this.accountLabelCiphertext = null;
  }

  UUID getId() {
    return id;
  }

  UUID getOrganizationId() {
    return organizationId;
  }

  UUID getUserId() {
    return userId;
  }

  UUID getProfileId() {
    return profileId;
  }

  ConnectedAccountState getState() {
    return state;
  }

  String getAccountLabelCiphertext() {
    return accountLabelCiphertext;
  }

  Instant getConnectedAt() {
    return connectedAt;
  }

  Instant getReconnectedAt() {
    return reconnectedAt;
  }

  @Override
  public String toString() {
    return "ConnectedAccount[id=" + id + ", profileId=" + profileId + ", state=" + state + "]";
  }
}
