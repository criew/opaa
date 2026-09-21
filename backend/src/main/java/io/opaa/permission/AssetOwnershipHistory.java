package io.opaa.permission;

import io.opaa.api.types.PermissionSubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A half-open interval {@code [validFrom, validTo)} recording one period an asset belonged to one
 * owner (#1815, ADR-0036 Entscheidung 8). {@code validTo == null} means the interval is open, i.e.
 * that owner holds the asset right now. Written and closed exclusively by {@link
 * AssetOwnershipHistoryService}; never updated in place except to set {@link #close}.
 *
 * <p>Type-independent like {@link AssetGrantHistory}: the asset is named by {@link AssetType} plus
 * id, so a second asset type needs no second table. Today {@code io.opaa.space} is the only writer
 * ({@code SPACE}); the ownership of a library follows with #1819.
 */
@Entity
@Table(name = "asset_ownership_history")
public class AssetOwnershipHistory {

  @Id private UUID id;

  @Convert(converter = AssetTypeConverter.class)
  @Column(name = "asset_type", nullable = false, length = 30)
  private AssetType assetType;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "owner_type", nullable = false, length = 20)
  private PermissionSubjectType ownerType;

  @Column(name = "owner_user_id")
  private UUID ownerUserId;

  @Column(name = "owner_group_id")
  private UUID ownerGroupId;

  @Enumerated(EnumType.STRING)
  @Column(name = "cause", nullable = false, length = 30)
  private AssetOwnershipHistoryCause cause;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  /**
   * The transfer this interval belongs to (#1834) - see {@link AssetGrantHistory#getTransferId}.
   */
  @Column(name = "transfer_id")
  private UUID transferId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AssetOwnershipHistory() {}

  AssetOwnershipHistory(
      AssetType assetType,
      UUID assetId,
      UUID organizationId,
      PermissionSubject owner,
      AssetOwnershipHistoryCause cause,
      UUID actorUserId,
      Instant validFrom) {
    this.id = UUID.randomUUID();
    this.assetType = assetType;
    this.assetId = assetId;
    this.organizationId = organizationId;
    this.ownerType = owner.type();
    this.ownerUserId = owner.type() == PermissionSubjectType.USER ? owner.id() : null;
    this.ownerGroupId = owner.type() == PermissionSubjectType.GROUP ? owner.id() : null;
    this.cause = cause;
    this.actorUserId = actorUserId;
    this.validFrom = validFrom;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public void close(Instant validTo) {
    this.validTo = validTo;
  }

  /** Marks this interval as one side of a transfer - see {@link #transferId}. */
  void belongsToTransfer(UUID transferId) {
    this.transferId = transferId;
  }

  public UUID getTransferId() {
    return transferId;
  }

  public UUID getId() {
    return id;
  }

  public AssetType getAssetType() {
    return assetType;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public PermissionSubjectType getOwnerType() {
    return ownerType;
  }

  public UUID getOwnerUserId() {
    return ownerUserId;
  }

  public UUID getOwnerGroupId() {
    return ownerGroupId;
  }

  public AssetOwnershipHistoryCause getCause() {
    return cause;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public Instant getValidFrom() {
    return validFrom;
  }

  public Instant getValidTo() {
    return validTo;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
