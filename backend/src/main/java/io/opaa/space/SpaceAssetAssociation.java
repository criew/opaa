package io.opaa.space;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A pure-curation association between a {@link Space} and an asset of any type (#203/#686, #1900,
 * see docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren). It names the asset by
 * its id on the asset shell ({@code fk_space_asset_associations_asset_organization}); the type is
 * the asset's own and stands in {@code assets} alone.
 *
 * <p><b>The association grants nothing.</b> It only records that an asset is curated into a space,
 * by whom and when - see {@code AssetAccessService#readableAssetIds}, which never consults this
 * table.
 */
@Entity
@Table(name = "space_asset_associations")
public class SpaceAssetAssociation {

  @Id private UUID id;

  @Column(name = "space_id", nullable = false)
  private UUID spaceId;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "created_by_user_id", nullable = false)
  private UUID createdByUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SpaceAssetAssociation() {}

  public SpaceAssetAssociation(
      UUID spaceId, UUID assetId, UUID organizationId, UUID createdByUserId) {
    this.id = UUID.randomUUID();
    this.spaceId = spaceId;
    this.assetId = assetId;
    this.organizationId = organizationId;
    this.createdByUserId = createdByUserId;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getSpaceId() {
    return spaceId;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getCreatedByUserId() {
    return createdByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
