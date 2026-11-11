package io.opaa.asset;

import io.opaa.api.types.AssetLockCause;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The responsible unit of an agent or a document template and its lock. A unit is a group or a
 * function, never both and never a person alone. {@code missingSince} starts the period after which
 * the object is locked for a missing unit; the lock keeps the object, it never deletes it.
 *
 * <p>The row is written through {@link AssetResponsibilityService}; the period run changes {@code
 * missingSince} and the automatic lock by conditional statements of {@link
 * AssetResponsibilityRepository}, so two instances never lock or audit the same object twice.
 */
@Entity
@Table(name = "asset_responsibilities")
public class AssetResponsibility {

  @Id
  @Column(name = "asset_id", nullable = false, updatable = false)
  private UUID assetId;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "responsible_group_id")
  private UUID responsibleGroupId;

  @Column(name = "responsible_function", length = 200)
  private String responsibleFunction;

  @Column(name = "missing_since")
  private Instant missingSince;

  @Column(name = "locked_at")
  private Instant lockedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "lock_cause", length = 20)
  private AssetLockCause lockCause;

  @Column(name = "lock_reason", length = 500)
  private String lockReason;

  protected AssetResponsibility() {}

  AssetResponsibility(UUID assetId, UUID organizationId, ResponsibleUnit unit) {
    this.assetId = assetId;
    this.organizationId = organizationId;
    assign(unit);
  }

  /** Names the unit; a named unit ends the period without one. */
  void assign(ResponsibleUnit unit) {
    this.responsibleGroupId = unit == null ? null : unit.groupId();
    this.responsibleFunction = unit == null ? null : unit.function();
    if (unit != null) {
      this.missingSince = null;
    }
  }

  void lock(AssetLockCause cause, String reason, Instant at) {
    this.lockCause = cause;
    this.lockReason = reason;
    this.lockedAt = at;
  }

  void unlock() {
    this.lockCause = null;
    this.lockReason = null;
    this.lockedAt = null;
  }

  /** The unit as stored, or {@code null} when none is named. */
  public ResponsibleUnit unit() {
    if (responsibleGroupId == null && responsibleFunction == null) {
      return null;
    }
    return new ResponsibleUnit(responsibleGroupId, responsibleFunction);
  }

  public boolean isLocked() {
    return lockedAt != null;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public UUID getResponsibleGroupId() {
    return responsibleGroupId;
  }

  public String getResponsibleFunction() {
    return responsibleFunction;
  }

  public Instant getMissingSince() {
    return missingSince;
  }

  public Instant getLockedAt() {
    return lockedAt;
  }

  public AssetLockCause getLockCause() {
    return lockCause;
  }

  public String getLockReason() {
    return lockReason;
  }
}
