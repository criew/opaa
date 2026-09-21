package io.opaa.permission;

import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
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
 * One installation-wide {@link Capability} held by one subject - an account, a group, or every
 * account of the organization at once (ADR-0036, Entscheidung 5). Unlike an {@link AssetGrant} this
 * row carries neither a role nor an expiry: a capability is a set element rather than a graded
 * role, and it is unbounded by definition - the bounded, person-bound permission is the Vollmacht,
 * a different mechanism that is never a capability.
 *
 * <p>The subject uses two nullable columns rather than one polymorphic id, so each column keeps a
 * real foreign key to its own target table; {@code ALL_ACCOUNTS} leaves both empty. {@code
 * chk_capability_grants_subject} enforces exactly the column matching {@link #getSubjectType()}.
 *
 * <p>Rows are inserted and deleted, never updated: capability plus subject are the identity of the
 * row, so a change of either is a different grant.
 */
@Entity
@Table(name = "capability_grants")
public class CapabilityGrant {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "capability", nullable = false, length = 40)
  private Capability capability;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_type", nullable = false, length = 20)
  private CapabilitySubjectType subjectType;

  @Column(name = "subject_user_id")
  private UUID subjectUserId;

  @Column(name = "subject_group_id")
  private UUID subjectGroupId;

  @Column(name = "granted_by_user_id")
  private UUID grantedByUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CapabilityGrant() {}

  private CapabilityGrant(
      UUID organizationId,
      Capability capability,
      CapabilitySubjectType subjectType,
      UUID subjectUserId,
      UUID subjectGroupId,
      UUID grantedByUserId) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.capability = capability;
    this.subjectType = subjectType;
    this.subjectUserId = subjectUserId;
    this.subjectGroupId = subjectGroupId;
    this.grantedByUserId = grantedByUserId;
  }

  public static CapabilityGrant forUser(
      UUID organizationId, Capability capability, UUID subjectUserId, UUID grantedByUserId) {
    return new CapabilityGrant(
        organizationId,
        capability,
        CapabilitySubjectType.USER,
        subjectUserId,
        null,
        grantedByUserId);
  }

  public static CapabilityGrant forGroup(
      UUID organizationId, Capability capability, UUID subjectGroupId, UUID grantedByUserId) {
    return new CapabilityGrant(
        organizationId,
        capability,
        CapabilitySubjectType.GROUP,
        null,
        subjectGroupId,
        grantedByUserId);
  }

  public static CapabilityGrant forAllAccounts(
      UUID organizationId, Capability capability, UUID grantedByUserId) {
    return new CapabilityGrant(
        organizationId,
        capability,
        CapabilitySubjectType.ALL_ACCOUNTS,
        null,
        null,
        grantedByUserId);
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Capability getCapability() {
    return capability;
  }

  public CapabilitySubjectType getSubjectType() {
    return subjectType;
  }

  public UUID getSubjectUserId() {
    return subjectUserId;
  }

  public UUID getSubjectGroupId() {
    return subjectGroupId;
  }

  /** The subject id, or {@code null} for {@link CapabilitySubjectType#ALL_ACCOUNTS}. */
  public UUID getSubjectId() {
    return switch (subjectType) {
      case USER -> subjectUserId;
      case GROUP -> subjectGroupId;
      case ALL_ACCOUNTS -> null;
    };
  }

  public UUID getGrantedByUserId() {
    return grantedByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
