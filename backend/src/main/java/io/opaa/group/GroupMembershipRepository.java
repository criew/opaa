package io.opaa.group;

import io.opaa.auth.ActiveAccountSql;
import io.opaa.permission.GroupMembershipSource;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Also the one implementation of {@link GroupMembershipSource}: the permission model asks the two
 * membership questions through that port, so {@code io.opaa.permission} needs no dependency on this
 * package (ADR-0036, Entscheidung 12). Spring Data implements the inherited methods from the
 * {@code @Query} declarations below.
 */
public interface GroupMembershipRepository
    extends JpaRepository<GroupMembership, UUID>, GroupMembershipSource {

  List<GroupMembership> findByGroupId(UUID groupId);

  Optional<GroupMembership> findByGroupIdAndUserId(UUID groupId, UUID userId);

  /** How many groups the account is a member of - part of what a handover moves. */
  long countByUserId(UUID userId);

  @Override
  @Query(
      "select m.group.id from GroupMembership m"
          + " where m.userId = :userId and m.group.deactivated = false")
  Set<UUID> findGroupIdsByUserId(@Param("userId") UUID userId);

  /**
   * Every group the user is a member of, deactivated ones included - what the administration of a
   * group shows its members ("Meine Gruppen", the selection's own groups), never what a rights
   * evaluation reads: that one asks {@link #findGroupIdsByUserId}.
   */
  @Query("select m.group.id from GroupMembership m where m.userId = :userId")
  Set<UUID> findGroupIdsOfMember(@Param("userId") UUID userId);

  @Override
  @Query(
      "select m.userId from GroupMembership m"
          + " where m.group.id = :groupId and m.organizationId = :organizationId"
          + " and m.group.deactivated = false")
  Set<UUID> findUserIdsByGroupIdAndOrganizationId(
      @Param("groupId") UUID groupId, @Param("organizationId") UUID organizationId);

  @Override
  @Query("select g.id from Group g where g.id in :groupIds and g.deactivated = true")
  Set<UUID> findDeactivatedGroupIdsIn(@Param("groupIds") Collection<UUID> groupIds);

  /**
   * One counting query instead of the member ids plus their accounts: the figure sits on the list
   * path of every space and every selection. The active-account half of the condition is {@link
   * ActiveAccountSql#PREDICATE}, so this query and {@code AccountActivityService} state the same
   * definition in one place each.
   */
  @Override
  @Query(
      value =
          "SELECT count(*) FROM group_memberships m JOIN users u ON u.id = m.user_id"
              + " WHERE m.group_id = :groupId AND m.organization_id = :organizationId AND "
              + ActiveAccountSql.PREDICATE,
      nativeQuery = true)
  int countActiveMembers(
      @Param("groupId") UUID groupId,
      @Param("organizationId") UUID organizationId,
      @Param("now") Instant now);

  /**
   * The same condition one page at a time, ordered by the name the answer shows - {@code
   * lower(...)} so the order does not depend on the database's collation for capital letters, and
   * the id as the tie-breaker so two accounts of the same name keep a stable position across pages.
   */
  @Override
  @Query(
      value =
          "SELECT m.user_id FROM group_memberships m JOIN users u ON u.id = m.user_id"
              + " WHERE m.group_id = :groupId AND m.organization_id = :organizationId AND "
              + ActiveAccountSql.PREDICATE
              + " ORDER BY lower(coalesce(u.display_name, u.email)), m.user_id"
              + " LIMIT :limit OFFSET :offset",
      nativeQuery = true)
  List<UUID> findActiveMemberIdsPage(
      @Param("groupId") UUID groupId,
      @Param("organizationId") UUID organizationId,
      @Param("now") Instant now,
      @Param("limit") int limit,
      @Param("offset") int offset);
}
