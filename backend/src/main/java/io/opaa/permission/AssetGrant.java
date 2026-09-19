package io.opaa.permission;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.PermissionSubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A grant of an {@link AssetRole} on one asset to a {@link PermissionSubject} - the actual
 * mechanism behind "readable assets" (#202, see
 * docs/features/spaces-and-assets.md#rechte-an-einem-asset-erhalten). Every grant carries an
 * optional {@code expiresAt} from the start, even though recertification only arrives with #241:
 * adding the field later is cheap, assessing an existing body of grants without one is not.
 *
 * <p><b>The asset is named by {@link AssetType} plus id, not by a column of one asset table</b>
 * (ADR-0036, Entscheidung 12): a second asset type needs no second rights logic. The price is that
 * {@code asset_id} carries no foreign key - the guarantees that dropped with it and what replaces
 * them are named in {@code changes/038-asset-grants-type-independent.yaml}.
 *
 * <p>The subject uses two nullable columns rather than one polymorphic id, so each column keeps a
 * real foreign key to its own target table instead of an unenforced UUID. {@code
 * chk_asset_grants_subject} enforces that exactly the column matching {@link #subjectType} is
 * non-null.
 */
@Entity
@Table(name = "asset_grants")
public class AssetGrant {

  @Id private UUID id;

  @Convert(converter = AssetTypeConverter.class)
  @Column(name = "asset_type", nullable = false, length = 30)
  private AssetType assetType;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_type", nullable = false, length = 20)
  private PermissionSubjectType subjectType;

  @Column(name = "subject_user_id")
  private UUID subjectUserId;

  @Column(name = "subject_group_id")
  private UUID subjectGroupId;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private AssetRole role;

  /** Null means the grant never expires. */
  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "granted_by_user_id")
  private UUID grantedByUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AssetGrant() {}

  private AssetGrant(
      AssetType assetType,
      UUID assetId,
      UUID organizationId,
      PermissionSubjectType subjectType,
      UUID subjectUserId,
      UUID subjectGroupId,
      AssetRole role,
      Instant expiresAt,
      UUID grantedByUserId) {
    this.id = UUID.randomUUID();
    this.assetType = assetType;
    this.assetId = assetId;
    this.organizationId = organizationId;
    this.subjectType = subjectType;
    this.subjectUserId = subjectUserId;
    this.subjectGroupId = subjectGroupId;
    this.role = role;
    this.expiresAt = expiresAt;
    this.grantedByUserId = grantedByUserId;
  }

  public static AssetGrant forUser(
      AssetType assetType,
      UUID assetId,
      UUID organizationId,
      UUID subjectUserId,
      AssetRole role,
      Instant expiresAt,
      UUID grantedByUserId) {
    return new AssetGrant(
        assetType,
        assetId,
        organizationId,
        PermissionSubjectType.USER,
        subjectUserId,
        null,
        role,
        expiresAt,
        grantedByUserId);
  }

  public static AssetGrant forGroup(
      AssetType assetType,
      UUID assetId,
      UUID organizationId,
      UUID subjectGroupId,
      AssetRole role,
      Instant expiresAt,
      UUID grantedByUserId) {
    return new AssetGrant(
        assetType,
        assetId,
        organizationId,
        PermissionSubjectType.GROUP,
        null,
        subjectGroupId,
        role,
        expiresAt,
        grantedByUserId);
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

  /**
   * Moves this grant to a new role, recording who conferred it. {@code granted_by_user_id} answers
   * "who procured this role", not "who created this row" - {@code
   * AssetAccessService#holdsIndependentOwnerRole} reads it to tell a self-issued {@link
   * AssetRole#OWNER} from one somebody else issued, and without carrying the changer forward an
   * administrator could raise a pre-existing foreign grant to {@code OWNER} and still appear as
   * "not self-granted".
   *
   * <p>Two changes rewrite {@code granted_by_user_id}: an actual role change, and the revival of an
   * already expired grant into an active one. Extending a still-running grant - or an identical
   * upsert - leaves the original conferrer in place, so extending one's own grant does not turn its
   * holder into its own conferrer. Revival must count, because an expired grant confers nothing:
   * whoever makes it effective again procures the role it carries, exactly as if they had created
   * the row.
   *
   * @param now the reference instant deciding whether this grant was expired before the change
   */
  public void updateRole(AssetRole role, Instant expiresAt, UUID grantedByUserId, Instant now) {
    boolean revived = isExpired(now) && (expiresAt == null || !expiresAt.isBefore(now));
    if (role != this.role || revived) {
      this.grantedByUserId = grantedByUserId;
    }
    this.role = role;
    this.expiresAt = expiresAt;
  }

  public boolean isExpired(Instant now) {
    return expiresAt != null && expiresAt.isBefore(now);
  }

  /** The subject this grant reaches, for {@link GroupMembershipResolver}. */
  public PermissionSubject subject() {
    UUID subjectId = subjectType == PermissionSubjectType.USER ? subjectUserId : subjectGroupId;
    return new PermissionSubject(subjectType, subjectId, organizationId);
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

  public PermissionSubjectType getSubjectType() {
    return subjectType;
  }

  public UUID getSubjectUserId() {
    return subjectUserId;
  }

  public UUID getSubjectGroupId() {
    return subjectGroupId;
  }

  /** The subject id regardless of {@link #subjectType}, for callers that only need "who". */
  public UUID getSubjectId() {
    return subjectType == PermissionSubjectType.USER ? subjectUserId : subjectGroupId;
  }

  public AssetRole getRole() {
    return role;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public UUID getGrantedByUserId() {
    return grantedByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
