package io.opaa.library;

import io.opaa.group.PermissionSubjectType;
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
 * A half-open interval {@code [validFrom, validTo)} recording one state {@link AssetGrant} was in
 * (#238, see docs/features/spaces-and-assets.md#nachweisbarkeit-historisierung-von-rechten). {@code
 * validTo == null} means the interval is still open, i.e. the grant is in this state right now.
 * Written and closed exclusively by {@link PermissionHistoryService}, never updated in place except
 * to set {@link #close}: a change closes the currently open row and opens a new one, so the table
 * is append-only from the outside.
 *
 * <p>{@link #expiresAt} is a copy of the live {@link AssetGrant#getExpiresAt()} at the moment this
 * interval was opened - not itself a bound on {@link #validTo}, so a Stichtag reconstruction can
 * additionally check it against the requested instant without needing a scheduled job to close
 * intervals the moment a grant's own expiry passes.
 */
@Entity
@Table(name = "asset_grant_history")
public class AssetGrantHistory {

  @Id private UUID id;

  @Column(name = "library_id", nullable = false)
  private UUID libraryId;

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

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "cause", nullable = false, length = 30)
  private AssetGrantHistoryCause cause;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "valid_from", nullable = false)
  private Instant validFrom;

  @Column(name = "valid_to")
  private Instant validTo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AssetGrantHistory() {}

  private AssetGrantHistory(
      UUID libraryId,
      UUID organizationId,
      PermissionSubjectType subjectType,
      UUID subjectUserId,
      UUID subjectGroupId,
      AssetRole role,
      Instant expiresAt,
      AssetGrantHistoryCause cause,
      UUID actorUserId,
      Instant validFrom) {
    this.id = UUID.randomUUID();
    this.libraryId = libraryId;
    this.organizationId = organizationId;
    this.subjectType = subjectType;
    this.subjectUserId = subjectUserId;
    this.subjectGroupId = subjectGroupId;
    this.role = role;
    this.expiresAt = expiresAt;
    this.cause = cause;
    this.actorUserId = actorUserId;
    this.validFrom = validFrom;
  }

  /** Opens a new interval for {@code grant} as it stands right now. */
  static AssetGrantHistory open(
      AssetGrant grant, AssetGrantHistoryCause cause, UUID actorUserId, Instant now) {
    return new AssetGrantHistory(
        grant.getLibraryId(),
        grant.getOrganizationId(),
        grant.getSubjectType(),
        grant.getSubjectUserId(),
        grant.getSubjectGroupId(),
        grant.getRole(),
        grant.getExpiresAt(),
        cause,
        actorUserId,
        now);
  }

  /**
   * A zero-length marker interval ({@code validFrom == validTo == at}) recording that {@code grant}
   * was revoked - {@link AssetGrantHistoryCause#REVOKED} never opens a lasting interval (a revoked
   * grant grants nothing from that instant on), but the revocation itself is still a permission
   * change with a triggering actor that #238's acceptance criteria require to be recorded, distinct
   * from the closing of the previous {@link AssetGrantHistoryCause#GRANTED}/ {@link
   * AssetGrantHistoryCause#ROLE_CHANGED} interval (whose own cause must stay unchanged - it really
   * was granted or role-changed at the time). Never selected by {@link
   * PermissionHistoryService#readableLibraryIdsAsOf} 's {@code validFrom <= asOf < validTo} check,
   * since that is never true for a zero-length interval.
   */
  static AssetGrantHistory terminal(
      AssetGrant grant, AssetGrantHistoryCause cause, UUID actorUserId, Instant at) {
    AssetGrantHistory marker = open(grant, cause, actorUserId, at);
    marker.close(at);
    return marker;
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  void close(Instant validTo) {
    this.validTo = validTo;
  }

  public UUID getId() {
    return id;
  }

  public UUID getLibraryId() {
    return libraryId;
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

  public AssetRole getRole() {
    return role;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public AssetGrantHistoryCause getCause() {
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
