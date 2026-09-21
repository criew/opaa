package io.opaa.group;

import io.opaa.api.types.GroupKind;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionSubject;
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
   * The provider this group originates from ({@code oidc_providers}), or null for an internal group
   * - the group's origin as a real foreign key rather than a namespace in {@link #externalId}
   * (ADR-0036, Entscheidung 2). Never set for {@link GroupKind#AD_HOC} and always set for {@link
   * GroupKind#IDENTITY_PROVIDER} ({@code chk_groups_provider_kind}); for {@link GroupKind#ORG_UNIT}
   * it names the provider the directory synchronisation runs for, which is absent while no default
   * provider exists.
   */
  @Column(name = "provider_id")
  private UUID providerId;

  /**
   * The source's stable identifier, matched on instead of the name so a rename at the source never
   * orphans grants: the directory's objectGUID or SCIM externalId for {@link GroupKind#ORG_UNIT},
   * the group name as the token's claim carries it for {@link GroupKind#IDENTITY_PROVIDER} - since
   * #1812 without a provider namespace, because {@link #providerId} carries the provider. Null only
   * for {@link GroupKind#AD_HOC}, which has no source outside this system.
   */
  @Column(name = "external_id", length = 255)
  private String externalId;

  /**
   * The path the source reports for this group ("/Haus/Abteilung 5/Referat 50"), null when the
   * source names none. Display only: it tells the same-named subgroups of one directory apart,
   * which neither the name nor the provider does.
   */
  @Column(name = "source_path", length = 1000)
  private String sourcePath;

  /**
   * The parent unit the directory reports for an {@link GroupKind#ORG_UNIT}; null for the other
   * kinds, which have no hierarchy. Stored and exposed as reported, never resolved into membership:
   * a member of a child unit is not a member of its parent.
   */
  @Column(name = "parent_group_id")
  private UUID parentGroupId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /**
   * Set when directory synchronisation (#237) no longer sees this {@link GroupKind#ORG_UNIT} in the
   * directory - a merge or reorganisation, not a deletion. Existing grants to a dissolved group
   * keep working for its current members; the group's membership is simply frozen at its
   * last-known-good state and never grows again through synchronisation. Always {@code false} for
   * the other two kinds: synchronisation reads {@link GroupKind#ORG_UNIT} groups alone, an {@link
   * GroupKind#AD_HOC} group has no directory counterpart to disappear from, and an {@link
   * GroupKind#IDENTITY_PROVIDER} group simply stops being refreshed.
   */
  @Column(name = "dissolved", nullable = false)
  private boolean dissolved;

  @Column(name = "dissolved_at")
  private Instant dissolvedAt;

  @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<GroupMembership> memberships = new ArrayList<>();

  protected Group() {}

  public Group(
      UUID organizationId,
      GroupKind kind,
      String name,
      String description,
      UUID providerId,
      String externalId,
      String sourcePath,
      UUID parentGroupId) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.kind = kind;
    this.name = name;
    this.description = description;
    this.providerId = providerId;
    this.externalId = externalId;
    this.sourcePath = sourcePath;
    this.parentGroupId = parentGroupId;
  }

  /** An internal group: no source outside this system, and therefore no provider. */
  public static Group internal(
      UUID organizationId, String name, String description, UUID parentGroupId) {
    return new Group(
        organizationId, GroupKind.AD_HOC, name, description, null, null, null, parentGroupId);
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

  /**
   * Applies a rename that originates from the directory (#237), never from a user - only the name
   * changes, because {@code description} is not a directory-owned field for {@link
   * GroupKind#ORG_UNIT} groups.
   */
  public void renameFromDirectory(String name) {
    this.name = name;
  }

  /**
   * Marks this group as no longer present in the directory (#237). Deliberately does not touch
   * {@link #memberships} - the group's reach is frozen at its last-known-good state, not revoked.
   */
  public void dissolve(Instant dissolvedAt) {
    this.dissolved = true;
    this.dissolvedAt = dissolvedAt;
  }

  /**
   * Reverses {@link #dissolve} when the directory reports this unit again after it had previously
   * disappeared (a reorganisation that later un-did itself). Membership is resynchronised
   * separately by the caller, the same way as for any other still-present group.
   */
  public void reactivate() {
    this.dissolved = false;
    this.dissolvedAt = null;
  }

  /**
   * Updates the parent organizational unit from directory synchronisation (#237). Applied in a
   * second pass after every group in a sync run has a persisted id, so it works regardless of the
   * order the directory reported groups in, and to existing groups (a reorganisation that reassigns
   * a unit under a different parent), not only newly created ones.
   */
  public void updateParentGroup(UUID parentGroupId) {
    this.parentGroupId = parentGroupId;
  }

  /**
   * Updates the path the directory reports for this group (#1817). Display-only, so it is refreshed
   * silently in the same second pass as the parent link: a path changes without the group itself
   * changing - renaming a parent moves every descendant's path.
   */
  public void updateSourcePath(String sourcePath) {
    this.sourcePath = sourcePath;
  }

  public boolean isOrgUnit() {
    return this.kind == GroupKind.ORG_UNIT;
  }

  public boolean isDissolved() {
    return this.dissolved;
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

  public UUID getProviderId() {
    return providerId;
  }

  public String getExternalId() {
    return externalId;
  }

  public String getSourcePath() {
    return sourcePath;
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

  public Instant getDissolvedAt() {
    return dissolvedAt;
  }

  public List<GroupMembership> getMemberships() {
    return Collections.unmodifiableList(memberships);
  }
}
