package io.opaa.succession;

import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.permission.AssetType;
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
 * The record behind one entry of the operational list (ADR-0036 Entscheidung 6) - <b>not the
 * state</b>: it carries when the state was first seen and when it ended, and nothing else. The
 * state itself stays derived, so no path can forget to clear it.
 *
 * <p>After the insert a record is only written through the conditional updates of {@link
 * SuccessionCaseRepository}: a sighting or close that loaded the record before a concurrent close
 * committed must not write the whole row back and reopen it.
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

  /** Set exactly for {@link SuccessionObjectType#ASSET}: the record outlives the asset it names. */
  @Column(name = "asset_type", length = 30)
  private String assetType;

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
      AssetType assetType,
      UUID objectId,
      Instant firstSeenAt) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.kind = kind;
    this.objectType = objectType;
    this.assetType = assetType == null ? null : assetType.value();
    this.objectId = objectId;
    this.firstSeenAt = firstSeenAt;
    this.lastSeenAt = firstSeenAt;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
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

  public AssetType getAssetType() {
    return assetType == null ? null : AssetType.of(assetType);
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
