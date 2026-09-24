package io.opaa.asset;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetVisibility;
import io.opaa.permission.AssetType;
import io.opaa.permission.AssetTypeConverter;
import io.opaa.permission.PermissionSubject;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * The asset shell (#1899, docs/features/spaces-and-assets.md#assets): everything an asset has
 * regardless of its type - name, description, one owner, release level ({@link #visibility}),
 * findability ({@link #listed}), origin and creator. A type adds its own table and entity by
 * extending this class ({@code JOINED}: the type row shares the shell's id); an asset whose type
 * maps no entity of its own loads as a plain {@code Asset}.
 *
 * <p>Exactly the owner column matching {@link #ownerType} is set ({@code chk_assets_owner}); each
 * carries a real foreign key, which a single polymorphic column could not. Whoever changes owner,
 * visibility or listed goes through {@link AssetShellService}, which writes the audit entry, the
 * history interval and applies the frozen-reach rule - the setters are package-private for that
 * reason.
 */
@Entity
@DynamicUpdate
@Table(name = "assets")
@Inheritance(strategy = InheritanceType.JOINED)
public class Asset implements OwnedAsset {

  @Id private UUID id;

  @Convert(converter = AssetTypeConverter.class)
  @Column(name = "asset_type", nullable = false, updatable = false, length = 30)
  private AssetType assetType;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "description", length = 2000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "owner_type", nullable = false, length = 20)
  private AssetOwnerType ownerType;

  @Column(name = "owner_user_id")
  private UUID ownerUserId;

  @Column(name = "owner_group_id")
  private UUID ownerGroupId;

  @Enumerated(EnumType.STRING)
  @Column(name = "visibility", nullable = false, length = 20)
  private AssetVisibility visibility;

  @Column(name = "listed", nullable = false)
  private boolean listed;

  @Enumerated(EnumType.STRING)
  @Column(name = "origin", nullable = false, length = 20)
  private AssetOrigin origin = AssetOrigin.LOCAL;

  /** Who created the asset - not its owner; {@code null} once that account is gone. */
  @Column(name = "created_by_user_id")
  private UUID createdByUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Asset() {}

  /**
   * A new asset of {@code assetType}, owned by exactly one of {@code ownerUserId}/{@code
   * ownerGroupId} as {@code ownerType} says.
   */
  protected Asset(
      AssetType assetType,
      UUID organizationId,
      String name,
      String description,
      AssetOwnerType ownerType,
      UUID ownerId,
      AssetVisibility visibility,
      boolean listed) {
    this.id = UUID.randomUUID();
    this.assetType = Objects.requireNonNull(assetType, "assetType");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
    this.name = name;
    this.description = description;
    this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
    this.ownerUserId = ownerType == AssetOwnerType.USER ? ownerId : null;
    this.ownerGroupId = ownerType == AssetOwnerType.GROUP ? ownerId : null;
    this.visibility = visibility;
    this.listed = listed;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  /** Marks the asset as changed, so a change that touches only the type table bumps it too. */
  protected void touch() {
    this.updatedAt = Instant.now();
  }

  /** Package-private by contract: set once, by {@link AssetShellService#registerCreated}. */
  void recordCreatedBy(UUID userId) {
    this.createdByUserId = userId;
  }

  /** Name and description - no reach field, so no history and no guard. */
  public void rename(String name, String description) {
    this.name = name;
    this.description = description;
  }

  /** Package-private by contract: only {@link AssetShellService} changes the reach. */
  void applyReach(AssetVisibility visibility, boolean listed) {
    this.visibility = Objects.requireNonNull(visibility, "visibility");
    this.listed = listed;
  }

  /**
   * Package-private by contract: only {@link AssetShellService} hands an asset on. Exactly the
   * column matching {@code ownerType} stays set, as {@code chk_assets_owner} demands.
   */
  void applyOwner(AssetOwnerType ownerType, UUID ownerId) {
    this.ownerType = ownerType;
    this.ownerUserId = ownerType == AssetOwnerType.USER ? ownerId : null;
    this.ownerGroupId = ownerType == AssetOwnerType.GROUP ? ownerId : null;
    touch();
  }

  public boolean isOwnedByUser(UUID userId) {
    return ownerType == AssetOwnerType.USER && ownerUserId.equals(userId);
  }

  public boolean isOwnedByGroup(UUID groupId) {
    return ownerType == AssetOwnerType.GROUP && ownerGroupId.equals(groupId);
  }

  /** The owning user or group id, whichever {@link #ownerType} points at. */
  public UUID getOwnerId() {
    return switch (ownerType) {
      case USER -> ownerUserId;
      case GROUP -> ownerGroupId;
    };
  }

  /** The owner as a rights subject of this asset's organization. */
  public PermissionSubject ownerSubject() {
    return ownerType == AssetOwnerType.GROUP
        ? PermissionSubject.group(ownerGroupId, organizationId)
        : PermissionSubject.user(ownerUserId, organizationId);
  }

  /** Whether the asset is released organization-wide - the third way of the rights formula. */
  public boolean isOrganizationWide() {
    return visibility == AssetVisibility.ORGANIZATION;
  }

  public UUID getId() {
    return id;
  }

  public AssetType getAssetType() {
    return assetType;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public AssetOwnerType getOwnerType() {
    return ownerType;
  }

  public UUID getOwnerUserId() {
    return ownerUserId;
  }

  public UUID getOwnerGroupId() {
    return ownerGroupId;
  }

  public AssetVisibility getVisibility() {
    return visibility;
  }

  public boolean isListed() {
    return listed;
  }

  public AssetOrigin getOrigin() {
    return origin;
  }

  public UUID getCreatedByUserId() {
    return createdByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
