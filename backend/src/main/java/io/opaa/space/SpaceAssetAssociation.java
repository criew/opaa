package io.opaa.space;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A pure-curation association between a {@link Space} and a knowledge library (#203/#686, see
 * docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren). The name stays generic
 * ("asset", not "library") so a future asset type (agent, prompt library) does not force a rename -
 * but only knowledge libraries exist as an asset type today (#201), so this entity carries {@code
 * libraryId} directly rather than a polymorphic (type, id) pair.
 *
 * <p><b>The association grants nothing.</b> It only records that a library is curated into a space,
 * by whom and when - see {@code LibraryAccessService#readableLibraryIds}, which never consults this
 * table. Effective read access to the library is entirely unaffected by whether it is associated
 * with any space at all.
 */
@Entity
@Table(name = "space_asset_associations")
public class SpaceAssetAssociation {

  @Id private UUID id;

  @Column(name = "space_id", nullable = false)
  private UUID spaceId;

  @Column(name = "library_id", nullable = false)
  private UUID libraryId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "created_by_user_id", nullable = false)
  private UUID createdByUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SpaceAssetAssociation() {}

  public SpaceAssetAssociation(
      UUID spaceId, UUID libraryId, UUID organizationId, UUID createdByUserId) {
    this.id = UUID.randomUUID();
    this.spaceId = spaceId;
    this.libraryId = libraryId;
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

  public UUID getLibraryId() {
    return libraryId;
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
