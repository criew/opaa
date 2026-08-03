package io.opaa.space;

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

  @Query("select distinct s from Space s left join fetch s.memberships where s.id = :spaceId")
  Optional<Space> findByIdWithMemberships(@Param("spaceId") UUID spaceId);

  boolean existsByOwnerIdAndKind(UUID ownerId, SpaceKind kind);

  /**
   * Inserts a personal space and its owner {@code ADMIN} membership in a single round trip,
   * silently doing nothing if a personal space for {@code ownerId} already exists - see {@link
   * SpaceService#ensurePersonalSpace} for the full reasoning (#201/#305 code review).
   *
   * <p>The single native statement below is a CTE chain, not two independent inserts: {@code
   * new_space} attempts the {@code spaces} insert with {@code ON CONFLICT (owner_id) WHERE kind =
   * 'PERSONAL' DO NOTHING} (the partial unique index {@code uk_spaces_personal_owner}, migration
   * 010) as the conflict target, and the {@code space_memberships} insert then {@code SELECT}s from
   * {@code new_space} - zero rows (and therefore no membership insert either) if the conflict
   * fired, exactly one row (and therefore exactly one membership insert) if it did not. A losing
   * caller's membership insert is correctly skipped without a second query to find out whether it
   * lost, because both inserts happen in the one statement Postgres evaluates as a whole.
   */
  @Modifying
  @Query(
      value =
          "WITH new_space AS ("
              + "  INSERT INTO spaces"
              + "    (id, name, description, kind, visibility, owner_id, organization_id, created_at, updated_at)"
              + "  VALUES"
              + "    (:spaceId, :name, :description, 'PERSONAL', 'PRIVATE', :ownerId, :organizationId, now(), now())"
              + "  ON CONFLICT (owner_id) WHERE kind = 'PERSONAL' DO NOTHING"
              + "  RETURNING id"
              + ") "
              + "INSERT INTO space_memberships (id, user_id, space_id, role, organization_id, created_at) "
              + "SELECT :membershipId, :ownerId, id, 'ADMIN', :organizationId, now() FROM new_space",
      nativeQuery = true)
  void insertPersonalSpaceIfAbsent(
      @Param("spaceId") UUID spaceId,
      @Param("membershipId") UUID membershipId,
      @Param("name") String name,
      @Param("description") String description,
      @Param("ownerId") UUID ownerId,
      @Param("organizationId") UUID organizationId);
}
