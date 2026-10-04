package io.opaa.asset;

import io.opaa.api.types.AssetOrigin;
import io.opaa.api.types.AssetOwnerType;
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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * The asset shell (#1899, docs/features/spaces-and-assets.md#assets): everything an asset has
 * regardless of its type - name, description, one owner, origin and creator. A type adds its own
 * table and entity by extending this class ({@code JOINED}: the type row shares the shell's id); an
 * asset whose type maps no entity of its own loads as a plain {@code Asset}.
 *
 * <p><b>How far the asset reaches is not a field here</b> (#1931, ADR-0037): it follows from the
 * grants alone, "Alle Konten" included. Whoever may not read an asset does not see it at all.
 *
 * <p>Exactly the owner column matching {@link #ownerType} is set ({@code chk_assets_owner}); each
 * carries a real foreign key, which a single polymorphic column could not. Whoever changes the
 * owner goes through the shell's transfer, which writes the audit entry and the history interval -
 * the setter is package-private for that reason.
 *
 * <p>An {@link #isOwnerOnly() owner-only} asset has exactly one reader, its owning person: the mark
 * is set at construction, and the database keeps it and the owner fixed and admits no grant but the
 * owner's own ({@code trg_assets_guard_owner_only}, {@code trg_asset_grants_guard_owner_only}).
 */
@Entity
@DynamicUpdate
@Table(name = "assets")
@Inheritance(strategy = InheritanceType.JOINED)
public class Asset implements OwnedAsset {

  /** The neutral name of an owner-only asset in every protocol (ADR-0041, Entscheidung 6). */
  public static final String PRIVATE_AUDIT_NAME = "Private Bibliothek";

  /** The payload keys that name no content: ids, kinds, states, flags and counts. */
  static final Set<String> NEUTRAL_AUDIT_KEYS =
      Set.of(
          "allAccountsGrantAllowed",
          "changedFields",
          "confidence",
          "datePrecision",
          "diagnosticsLocked",
          "documentId",
          "documentsRemoved",
          "expiresAt",
          "externalAccess",
          "extractionVersion",
          "fieldKey",
          "modelId",
          "origin",
          "ownerId",
          "requestedCount",
          "role",
          "sourceType",
          "spaceId",
          "state",
          "subjectType");

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
  @Column(name = "origin", nullable = false, length = 20)
  private AssetOrigin origin = AssetOrigin.LOCAL;

  /** Who created the asset - not its owner; {@code null} once that account is gone. */
  @Column(name = "created_by_user_id")
  private UUID createdByUserId;

  @Column(name = "owner_only", nullable = false, updatable = false)
  private boolean ownerOnly;

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
      UUID ownerId) {
    this(assetType, organizationId, name, description, ownerType, ownerId, false);
  }

  /** A new asset as above, {@code ownerOnly} or not; an owner-only asset is owned by a person. */
  protected Asset(
      AssetType assetType,
      UUID organizationId,
      String name,
      String description,
      AssetOwnerType ownerType,
      UUID ownerId,
      boolean ownerOnly) {
    if (ownerOnly && ownerType != AssetOwnerType.USER) {
      throw new IllegalArgumentException("an owner-only asset is owned by a person");
    }
    this.ownerOnly = ownerOnly;
    this.id = UUID.randomUUID();
    this.assetType = Objects.requireNonNull(assetType, "assetType");
    this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
    this.name = name;
    this.description = description;
    this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
    this.ownerUserId = ownerType == AssetOwnerType.USER ? ownerId : null;
    this.ownerGroupId = ownerType == AssetOwnerType.GROUP ? ownerId : null;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  /**
   * Marks a change of master data or content. {@code updatedAt} moves only here and at creation, so
   * a technical write - a reminder sent, an automatic expiry - leaves it standing.
   */
  protected void touch() {
    this.updatedAt = Instant.now();
  }

  /** Package-private by contract: set once, by {@link AssetShellService#registerCreated}. */
  void recordCreatedBy(UUID userId) {
    this.createdByUserId = userId;
  }

  /** Name and description - neither is a reach field, so no history and no guard. */
  public void rename(String name, String description) {
    this.name = name;
    this.description = description;
    touch();
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

  /**
   * The name a protocol entry carries: for an {@link #isOwnerOnly() owner-only} asset the neutral
   * {@link #PRIVATE_AUDIT_NAME}, identified by its id alone - its own name is its owner's.
   */
  public String auditName() {
    return ownerOnly ? PRIVATE_AUDIT_NAME : name;
  }

  /**
   * The payload a protocol entry about this asset carries: for an owner-only asset only the keys of
   * {@link #NEUTRAL_AUDIT_KEYS} - ids, kinds, states and counts, never a name, path or value.
   */
  public Map<String, Object> auditPayload(Map<String, Object> payload) {
    if (!ownerOnly || payload == null) {
      return payload;
    }
    Map<String, Object> neutral = new LinkedHashMap<>();
    payload.forEach(
        (key, value) -> {
          if (NEUTRAL_AUDIT_KEYS.contains(key)) {
            neutral.put(key, value);
          }
        });
    return neutral;
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

  public AssetOrigin getOrigin() {
    return origin;
  }

  @Override
  public boolean isOwnerOnly() {
    return ownerOnly;
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
