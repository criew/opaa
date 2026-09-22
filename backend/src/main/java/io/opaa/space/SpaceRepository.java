package io.opaa.space;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpaceRepository extends JpaRepository<Space, UUID> {

  List<Space> findDistinctByMembershipsUserId(UUID userId);

  @Query(
      "select distinct s from Space s "
          + "left join fetch s.memberships "
          + "where s.id in (select m.space.id from SpaceMembership m where m.userId = :userId)")
  List<Space> findDistinctByMembershipsUserIdWithMemberships(@Param("userId") UUID userId);

  /**
   * The spaces one of the caller's groups is a member of (#1815). Separate from the query above
   * rather than one disjunction: a caller who belongs to no group would otherwise have to send an
   * empty {@code IN} list. Never called with an empty set - see {@code SpaceService#listSpaces}.
   */
  @Query(
      "select distinct s from Space s "
          + "left join fetch s.memberships "
          + "where s.id in (select m.space.id from SpaceMembership m where m.groupId in :groupIds)")
  List<Space> findDistinctByMembershipGroupIdsWithMemberships(
      @Param("groupIds") Collection<UUID> groupIds);

  @Query("select distinct s from Space s left join fetch s.memberships where s.id = :spaceId")
  Optional<Space> findByIdWithMemberships(@Param("spaceId") UUID spaceId);

  /**
   * Every space of one organization with its memberships - what the operational list of #1819 asks
   * after a capable ADMIN of. The fetch join is the point: deciding that question per space without
   * it is one query per row.
   */
  @Query(
      "select distinct s from Space s left join fetch s.memberships "
          + "where s.organizationId = :organizationId")
  List<Space> findByOrganizationIdWithMemberships(@Param("organizationId") UUID organizationId);

  boolean existsByOwnerIdAndIsDefaultTrue(UUID ownerId);

  /** Every space the user owns - what deleting a local account has to be clear of (#1537). */
  List<Space> findByOwnerId(UUID ownerId);

  /**
   * Inserts the user's default space and its owner {@code ADMIN} membership in a single round trip,
   * silently doing nothing if a default space for {@code ownerId} already exists - see {@link
   * SpaceService#ensureDefaultSpace} for the full reasoning (#201/#305 code review).
   *
   * <p>The single native statement below is a CTE chain, not two independent inserts: {@code
   * new_space} attempts the {@code spaces} insert with {@code ON CONFLICT (owner_id) WHERE
   * is_default DO NOTHING} (the partial unique index {@code uk_spaces_default_owner}, migration
   * 015) as the conflict target, and the {@code space_memberships} insert then {@code SELECT}s from
   * {@code new_space} - zero rows (and therefore no membership insert either) if the conflict
   * fired, exactly one row (and therefore exactly one membership insert) if it did not. A losing
   * caller's membership insert is correctly skipped without a second query to find out whether it
   * lost, because both inserts happen in the one statement Postgres evaluates as a whole.
   *
   * <p><b>No rights-history interval is written here</b> (#1815, ADR-0036 Entscheidung 8), and that
   * is the same rule {@code UserRepository#countDeletionBlockers} already applies with {@code
   * spaces ... AND is_default = false}: the personal space and everything provisioned with it are
   * the account itself, not a right anybody decided, and they go with the account when it is
   * deleted. Writing the intervals here would make every account that ever signed in permanently
   * undeletable through their {@code RESTRICT} person columns, without any decision having been
   * recorded. Consequence, named rather than hidden: a Stichtag reconstruction ({@link
   * SpaceMembershipHistoryService#spaceIdsAsOf}) does not report the personal space. Every
   * membership and every ownership a person decides on - including one in a personal space - goes
   * through {@link SpaceService} and is historised there.
   */
  @Modifying
  @Query(
      value =
          "WITH new_space AS ("
              + "  INSERT INTO spaces"
              + "    (id, name, description, is_default, visibility, owner_id, organization_id, created_at, updated_at)"
              + "  VALUES"
              + "    (:spaceId, :name, :description, true, 'PRIVATE', :ownerId, :organizationId, now(), now())"
              + "  ON CONFLICT (owner_id) WHERE is_default DO NOTHING"
              + "  RETURNING id"
              + ") "
              + "INSERT INTO space_memberships"
              + "  (id, subject_type, user_id, space_id, role, organization_id, created_at) "
              + "SELECT :membershipId, 'USER', :ownerId, id, 'ADMIN', :organizationId, now() FROM new_space",
      nativeQuery = true)
  void insertDefaultSpaceIfAbsent(
      @Param("spaceId") UUID spaceId,
      @Param("membershipId") UUID membershipId,
      @Param("name") String name,
      @Param("description") String description,
      @Param("ownerId") UUID ownerId,
      @Param("organizationId") UUID organizationId);
}
