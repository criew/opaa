package io.opaa.permission;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One object a {@link PermissionTransfer} touched, so its sharing view can name the operation
 * ("übertragen am 14.03.2026, Vorgang …") without a union over the four history tables on every
 * page view. The asset is named by {@link AssetType} plus id and carries no foreign key (ADR-0016):
 * the record outlives the object.
 */
@Entity
@Table(name = "permission_transfer_objects")
public class PermissionTransferObject {

  @Id private UUID id;

  @Column(name = "transfer_id", nullable = false)
  private UUID transferId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Convert(converter = AssetTypeConverter.class)
  @Column(name = "asset_type", nullable = false, length = 30)
  private AssetType assetType;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PermissionTransferObject() {}

  PermissionTransferObject(
      UUID transferId, UUID organizationId, AssetType assetType, UUID assetId) {
    this.id = UUID.randomUUID();
    this.transferId = transferId;
    this.organizationId = organizationId;
    this.assetType = assetType;
    this.assetId = assetId;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getTransferId() {
    return transferId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public AssetType getAssetType() {
    return assetType;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
