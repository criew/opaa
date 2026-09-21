package io.opaa.permission;

import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.PermissionTransferScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One carried-out transfer of rights from one subject to another (#1834, ADR-0036 Entscheidung 10)
 * - the "Vorgangskennung" every history row written by it carries and every object it touched
 * points at.
 *
 * <p>The subject ids deliberately carry no foreign key: the point of the operation is that the
 * source holds nothing afterwards and can be deleted. {@link #sourceLabel} is the name snapshot
 * that keeps the row readable once it is, and it stays {@code null} for a person - the name of
 * somebody who left is not repeated at every object they once owned.
 */
@Entity
@Table(name = "permission_transfers")
public class PermissionTransfer {

  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 20)
  private PermissionSubjectType sourceType;

  @Column(name = "source_user_id")
  private UUID sourceUserId;

  @Column(name = "source_group_id")
  private UUID sourceGroupId;

  @Column(name = "source_label")
  private String sourceLabel;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_type", nullable = false, length = 20)
  private PermissionSubjectType targetType;

  @Column(name = "target_user_id")
  private UUID targetUserId;

  @Column(name = "target_group_id")
  private UUID targetGroupId;

  @Column(name = "target_label")
  private String targetLabel;

  @Column(name = "scope", nullable = false, length = 200)
  private String scope;

  @Column(name = "grant_count", nullable = false)
  private int grantCount;

  @Column(name = "space_membership_count", nullable = false)
  private int spaceMembershipCount;

  @Column(name = "capability_count", nullable = false)
  private int capabilityCount;

  @Column(name = "ownership_count", nullable = false)
  private int ownershipCount;

  @Column(name = "stewardship_count", nullable = false)
  private int stewardshipCount;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "performed_at", nullable = false)
  private Instant performedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PermissionTransfer() {}

  PermissionTransfer(
      UUID organizationId,
      PermissionSubject source,
      String sourceLabel,
      PermissionSubject target,
      String targetLabel,
      Set<PermissionTransferScope> scope,
      UUID actorUserId,
      Instant performedAt) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.sourceType = source.type();
    this.sourceUserId = source.type() == PermissionSubjectType.USER ? source.id() : null;
    this.sourceGroupId = source.type() == PermissionSubjectType.GROUP ? source.id() : null;
    this.sourceLabel = source.type() == PermissionSubjectType.GROUP ? sourceLabel : null;
    this.targetType = target.type();
    this.targetUserId = target.type() == PermissionSubjectType.USER ? target.id() : null;
    this.targetGroupId = target.type() == PermissionSubjectType.GROUP ? target.id() : null;
    this.targetLabel = target.type() == PermissionSubjectType.GROUP ? targetLabel : null;
    this.scope = encodeScope(scope);
    this.actorUserId = actorUserId;
    this.performedAt = performedAt;
  }

  /** The stored form of the scope: the part names in enum order, comma separated. */
  static String encodeScope(Set<PermissionTransferScope> scope) {
    return EnumSet.copyOf(scope).stream().map(Enum::name).collect(Collectors.joining(","));
  }

  @PrePersist
  void onCreate() {
    this.createdAt = Instant.now();
  }

  void recordCounts(PermissionTransferCounts counts) {
    this.grantCount = counts.assetGrants();
    this.spaceMembershipCount = counts.spaceMemberships();
    this.capabilityCount = counts.capabilities();
    this.ownershipCount = counts.ownedAssets();
    this.stewardshipCount = counts.stewardships();
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public PermissionSubject source() {
    return new PermissionSubject(
        sourceType,
        sourceType == PermissionSubjectType.USER ? sourceUserId : sourceGroupId,
        organizationId);
  }

  public PermissionSubject target() {
    return new PermissionSubject(
        targetType,
        targetType == PermissionSubjectType.USER ? targetUserId : targetGroupId,
        organizationId);
  }

  /** The source group's name at the time of the transfer; {@code null} for a person. */
  public String getSourceLabel() {
    return sourceLabel;
  }

  public String getTargetLabel() {
    return targetLabel;
  }

  public Set<PermissionTransferScope> getScope() {
    return Arrays.stream(scope.split(","))
        .map(PermissionTransferScope::valueOf)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public PermissionTransferCounts counts() {
    return new PermissionTransferCounts(
        grantCount, spaceMembershipCount, capabilityCount, ownershipCount, stewardshipCount);
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public Instant getPerformedAt() {
    return performedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
