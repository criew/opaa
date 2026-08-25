package io.opaa.library;

import io.opaa.group.PermissionSubjectType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetGrantRepository extends JpaRepository<AssetGrant, UUID> {

  /** All grants on a library, used to populate {@link LibraryAccessService}'s per-library cache. */
  List<AssetGrant> findByLibraryId(UUID libraryId);

  /**
   * All grants across every one of {@code libraryIds} in a single query - used by {@link
   * LibraryAccessService#effectiveRolesForReadableLibraries} to compute {@code myRole} for a whole
   * {@code listLibraries} response without either an N+1 query per library or the per-library
   * {@link #findByLibraryId} cache, whose staleness window could otherwise disagree with the
   * fresher, uncached {@link #findReadableLibraryIdsByDirectGrant}/{@link
   * #findReadableLibraryIdsByGroupGrant} that decide list membership and leave a listed library
   * with no resolvable role.
   */
  List<AssetGrant> findByLibraryIdIn(Set<UUID> libraryIds);

  /**
   * A namespace for {@link #lockLibraryGrantsForMutation}'s Postgres advisory locks, arbitrary but
   * fixed and documented so a future, unrelated advisory lock elsewhere in the codebase can pick a
   * different one instead of colliding.
   */
  int ASSET_GRANT_MUTATION_LOCK_NAMESPACE = 202;

  /**
   * Acquires a transaction-scoped Postgres advisory lock keyed on {@code libraryId} (namespaced
   * under {@link #ASSET_GRANT_MUTATION_LOCK_NAMESPACE}), serializing every concurrent grant
   * mutation on the same library - called first, before {@link #countOtherActiveOwnerGrants}, by
   * {@code AssetGrantService}'s last-active-OWNER guard.
   *
   * <p><b>Advisory lock, not {@code SELECT ... FOR UPDATE} on the grant rows:</b> row-locking every
   * grant of the library directly fixes the entity-staleness bug (see {@link
   * #countOtherActiveOwnerGrants}'s Javadoc) but deadlocks two concurrent mutations on the same
   * library, each trying to lock every grant row - even with a deterministic {@code ORDER BY id},
   * since Postgres executes the {@code Sort} before the {@code LockRows} step for that query shape.
   * A single advisory lock per library has only one lock to acquire, removing the whole class of
   * deadlock. Automatically released at transaction end (commit or rollback), so it cannot be
   * leaked like a {@code pg_advisory_lock}/{@code pg_advisory_unlock} pair would risk. This is a
   * real database lock, not an in-process one - see {@code io.opaa.auth.UserService
   * #provisioningLockFor} for why that distinction matters here (multiple application instances).
   */
  @Query(
      value =
          "SELECT 1 FROM (SELECT pg_advisory_xact_lock("
              + ASSET_GRANT_MUTATION_LOCK_NAMESPACE
              + ", hashtext(CAST(:libraryId AS text)))) acquired",
      nativeQuery = true)
  int lockLibraryGrantsForMutation(@Param("libraryId") UUID libraryId);

  /**
   * The number of active {@code OWNER} grants on {@code libraryId}, excluding {@code
   * excludingGrantId} - used only by {@code AssetGrantService}'s last-active-OWNER guard, and only
   * after {@link #lockLibraryGrantsForMutation} has been called in the same transaction for the
   * same {@code libraryId}; see that method's Javadoc for why a plain scalar read is safe to rely
   * on once the advisory lock is held.
   *
   * <p><b>Scalar aggregate, not an entity list:</b> {@code AssetGrantService}'s caller resolves
   * {@code effectiveRole} first, which populates {@link LibraryAccessService}'s cache via {@link
   * #findByLibraryId} inside the same transaction - an entity-list read here would hit Hibernate's
   * first-level cache and return those now-possibly-stale managed instances instead of the current
   * row values, letting the guard decide on outdated data. A plain scalar query has no entity
   * identity to resolve against the persistence context, so it always reflects the row values as
   * they stand at the time it runs.
   */
  @Query(
      value =
          "SELECT count(*) FROM asset_grants"
              + " WHERE library_id = :libraryId"
              + "   AND role = 'OWNER'"
              + "   AND (expires_at IS NULL OR expires_at > :now)"
              + "   AND id <> :excludingGrantId",
      nativeQuery = true)
  long countOtherActiveOwnerGrants(
      @Param("libraryId") UUID libraryId,
      @Param("excludingGrantId") UUID excludingGrantId,
      @Param("now") Instant now);

  Optional<AssetGrant> findByLibraryIdAndSubjectTypeAndSubjectUserId(
      UUID libraryId, PermissionSubjectType subjectType, UUID subjectUserId);

  Optional<AssetGrant> findByLibraryIdAndSubjectTypeAndSubjectGroupId(
      UUID libraryId, PermissionSubjectType subjectType, UUID subjectGroupId);

  /**
   * Every library id the given user can read via a direct grant, not expired as of {@code now}.
   * Only used as one branch of {@link LibraryAccessService#readableLibraryIds} - deliberately not
   * cached (unlike the per-library grant cache used for single-library checks): the vector search
   * filter must reflect a revoked grant on the very next query, and a single indexed query is
   * simpler and safer than a second cache-invalidation path to keep correct.
   */
  @Query(
      "select g.libraryId from AssetGrant g "
          + "where g.subjectType = io.opaa.group.PermissionSubjectType.USER "
          + "and g.subjectUserId = :userId and g.organizationId = :organizationId "
          + "and (g.expiresAt is null or g.expiresAt > :now)")
  Set<UUID> findReadableLibraryIdsByDirectGrant(
      @Param("userId") UUID userId,
      @Param("organizationId") UUID organizationId,
      @Param("now") Instant now);

  /** The group-grant counterpart of {@link #findReadableLibraryIdsByDirectGrant}. */
  @Query(
      "select g.libraryId from AssetGrant g "
          + "where g.subjectType = io.opaa.group.PermissionSubjectType.GROUP "
          + "and g.subjectGroupId in :groupIds and g.organizationId = :organizationId "
          + "and (g.expiresAt is null or g.expiresAt > :now)")
  Set<UUID> findReadableLibraryIdsByGroupGrant(
      @Param("groupIds") Set<UUID> groupIds,
      @Param("organizationId") UUID organizationId,
      @Param("now") Instant now);

  /**
   * Whether the given group is the subject of any grant, on any library - used by {@code
   * GroupService#deleteGroup} to reject deleting a group that still holds a grant ({@code
   * fk_asset_grants_subject_group_organization} is RESTRICT, so without this check the delete would
   * surface as an unhandled {@code DataIntegrityViolationException} -> HTTP 500). Deliberately a
   * second, independent check next to {@code KnowledgeLibraryRepository#existsByOwnerGroupId}
   * (ownership), not a replacement for it - a group can be both the owner of a library and hold a
   * grant on an unrelated one; deleting it must be rejected for either reason.
   */
  boolean existsBySubjectGroupId(UUID subjectGroupId);
}
