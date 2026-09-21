package io.opaa.succession;

import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The record behind one entry of the operational list (#1819, ADR-0036 Entscheidung 6) - <b>not the
 * state</b>: it carries when the state was first seen and when it ended, and nothing else. The
 * state itself stays derived, so no path can forget to clear it.
 */
@Entity
@Table(name = "succession_cases")
public class SuccessionCase {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 40)
  private SuccessionKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "object_type", nullable = false, length = 30)
  private SuccessionObjectType objectType;

  @Column(name = "object_id", nullable = false)
  private UUID objectId;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "closed_by_user_id")
  private UUID closedByUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SuccessionCase() {}

  SuccessionCase(
      UUID organizationId,
      SuccessionKind kind,
      SuccessionObjectType objectType,
      UUID objectId,
      Instant firstSeenAt) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.kind = kind;
    this.objectType = objectType;
    this.objectId = objectId;
    this.firstSeenAt = firstSeenAt;
    this.lastSeenAt = firstSeenAt;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  /** The run saw the state again - the age keeps running from {@link #firstSeenAt}. */
  void seenAt(Instant at) {
    this.lastSeenAt = at;
  }

  /**
   * The state is over. {@code endedBy} is the person whose action ended it where that is known - a
   * transfer names itself; a state that simply ceased to hold (a group has an active member again)
   * is closed by the run and names nobody.
   */
  void close(Instant at, UUID endedBy) {
    this.closedAt = at;
    this.closedByUserId = endedBy;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public SuccessionKind getKind() {
    return kind;
  }

  public SuccessionObjectType getObjectType() {
    return objectType;
  }

  public UUID getObjectId() {
    return objectId;
  }

  public Instant getFirstSeenAt() {
    return firstSeenAt;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public Instant getClosedAt() {
    return closedAt;
  }

  public UUID getClosedByUserId() {
    return closedByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
