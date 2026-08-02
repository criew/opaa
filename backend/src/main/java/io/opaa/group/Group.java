package io.opaa.group;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A permission subject that groups users together, scoped to exactly one organization. A grant can
 * reference a group instead of a single user (see {@link PermissionSubject}); resolving which users
 * a group grant reaches goes through {@link GroupMembershipResolver}.
 */
@Entity
@Table(name = "groups")
public class Group {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 20)
  private GroupKind kind;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "description", length = 2000)
  private String description;

  /**
   * Stable directory identifier (objectGUID or SCIM externalId) that directory synchronisation
   * (#237) matches on instead of the name - a rename in the directory must never orphan grants.
   * Null for {@link GroupKind#AD_HOC} groups, which have no directory counterpart.
   */
  @Column(name = "external_id", length = 255)
  private String externalId;

  /**
   * Parent organizational unit; only meaningful for {@link GroupKind#ORG_UNIT} groups. Used to
   * escalate curator responsibility upward when a unit has no curator of its own (see #208).
   */
  @Column(name = "parent_group_id")
  private UUID parentGroupId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<GroupMembership> memberships = new ArrayList<>();

  protected Group() {}

  public Group(
      UUID organizationId,
      GroupKind kind,
      String name,
      String description,
      String externalId,
      UUID parentGroupId) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.kind = kind;
    this.name = name;
    this.description = description;
    this.externalId = externalId;
    this.parentGroupId = parentGroupId;
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

  public void addMembership(GroupMembership membership) {
    memberships.add(membership);
    membership.assignGroup(this);
  }

  public void removeMembership(GroupMembership membership) {
    memberships.remove(membership);
    membership.assignGroup(null);
  }

  public void updateDetails(String name, String description) {
    this.name = name;
    this.description = description;
  }

  public boolean isOrgUnit() {
    return this.kind == GroupKind.ORG_UNIT;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public GroupKind getKind() {
    return kind;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public String getExternalId() {
    return externalId;
  }

  public UUID getParentGroupId() {
    return parentGroupId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public List<GroupMembership> getMemberships() {
    return Collections.unmodifiableList(memberships);
  }
}
