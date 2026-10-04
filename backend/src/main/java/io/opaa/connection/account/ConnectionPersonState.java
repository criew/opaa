package io.opaa.connection.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The lifecycle of one person's connections as the last reconciliation found it: since when the
 * account is deactivated, since when its connections rest, at most one of both. Only {@link
 * ConnectionLifecycleReconciler} writes it.
 */
@Entity
@Table(name = "connection_person_states")
class ConnectionPersonState {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "deactivated_since")
  private Instant deactivatedSince;

  @Column(name = "dormant_since")
  private Instant dormantSince;

  @Column(name = "checked_at", nullable = false)
  private Instant checkedAt;

  protected ConnectionPersonState() {}

  ConnectionPersonState(UUID userId) {
    this.userId = userId;
  }

  /** Deactivated from now on, unless it already was: the earlier start stays. */
  void deactivated(Instant now) {
    if (deactivatedSince == null) {
      deactivatedSince = now;
    }
    dormantSince = null;
    checkedAt = now;
  }

  /** Resting from now on, unless it already was; a resting person is no longer deactivated. */
  void dormant(Instant now) {
    if (dormantSince == null) {
      dormantSince = now;
    }
    deactivatedSince = null;
    checkedAt = now;
  }

  void usable(Instant now) {
    deactivatedSince = null;
    dormantSince = null;
    checkedAt = now;
  }

  UUID getUserId() {
    return userId;
  }

  Instant getDeactivatedSince() {
    return deactivatedSince;
  }

  Instant getDormantSince() {
    return dormantSince;
  }
}
