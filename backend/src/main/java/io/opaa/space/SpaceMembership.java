package io.opaa.space;

import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SpaceRole;
import io.opaa.permission.PermissionSubject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One membership of a space, held by a person or by a group (#1815, ADR-0036 Entscheidung 6). The
 * two subject columns and the "exactly one of them" check are the same shape {@code AssetGrant}
 * uses, so both rights axes address a subject identically.
 *
 * <p>A group membership confers its role on every member of the group without a row of their own,
 * and the role ends the moment they leave the group - see {@link SpaceAccessPolicy#effectiveRole}.
 */
@Entity
@Table(name = "space_memberships")
public class SpaceMembership {

  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_type", nullable = false, length = 20)
  private PermissionSubjectType subjectType;

  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "group_id")
  private UUID groupId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "space_id", nullable = false)
  private Space space;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private SpaceRole role;

  /**
   * The number of active accounts the group reached when it was admitted (ADR-0036, Entscheidung
   * 9). Null on a person's membership, and never written again afterwards: its whole value lies in
   * the comparison with the figure of today.
   */
  @Column(name = "member_count_at_grant")
  private Integer memberCountAtGrant;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected SpaceMembership() {}

  private SpaceMembership(
      PermissionSubjectType subjectType,
      UUID userId,
      UUID groupId,
      SpaceRole role,
      Integer memberCountAtGrant,
      UUID organizationId) {
    this.id = UUID.randomUUID();
    this.subjectType = subjectType;
    this.userId = userId;
    this.groupId = groupId;
    this.role = role;
    this.memberCountAtGrant = memberCountAtGrant;
    this.organizationId = organizationId;
  }

  public static SpaceMembership ofUser(UUID userId, SpaceRole role, UUID organizationId) {
    return new SpaceMembership(
        PermissionSubjectType.USER, userId, null, role, null, organizationId);
  }

  public static SpaceMembership ofGroup(
      UUID groupId, SpaceRole role, int activeMemberCount, UUID organizationId) {
    return new SpaceMembership(
        PermissionSubjectType.GROUP, null, groupId, role, activeMemberCount, organizationId);
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  void assignSpace(Space space) {
    this.space = space;
  }

  /** The rights subject this membership names - a user or a group, never both. */
  public PermissionSubject subject() {
    return new PermissionSubject(subjectType, subjectId(), organizationId);
  }

  /** The id of the named subject, whichever of the two columns carries it. */
  public UUID subjectId() {
    return subjectType == PermissionSubjectType.USER ? userId : groupId;
  }

  public boolean isUserSubject() {
    return subjectType == PermissionSubjectType.USER;
  }

  public boolean isGroupSubject() {
    return subjectType == PermissionSubjectType.GROUP;
  }

  public UUID getId() {
    return id;
  }

  public PermissionSubjectType getSubjectType() {
    return subjectType;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getGroupId() {
    return groupId;
  }

  public Space getSpace() {
    return space;
  }

  public SpaceRole getRole() {
    return role;
  }

  public void setRole(SpaceRole role) {
    this.role = role;
  }

  public Integer getMemberCountAtGrant() {
    return memberCountAtGrant;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
