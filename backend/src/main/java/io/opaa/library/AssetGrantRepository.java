package io.opaa.library;

import io.opaa.group.PermissionSubjectType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetGrantRepository extends JpaRepository<AssetGrant, UUID> {

  /**
   * Grants the owner of a personal library {@link AssetRole#OWNER} on it, in a single
   * insert-or-noop round trip keyed off the library row itself rather than a passed-in library id -
   * mirrors {@link KnowledgeLibraryRepository#insertPersonalLibraryIfAbsent}'s {@code ON CONFLICT
   * ... DO NOTHING} race handling, targeting the partial unique index {@code
   * uk_asset_grants_user_subject} (migration 013) so a concurrent call for the same user is a
   * silent no-op rather than a duplicate-grant error. Used only by {@code
   * KnowledgeLibraryService#ensurePersonalLibrary}, after the library itself has been
   * inserted-or-confirmed-existing in the same {@code REQUIRES_NEW} transaction.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO asset_grants"
              + "  (id, library_id, organization_id, subject_type, subject_user_id, role, granted_by_user_id, created_at, updated_at)"
              + " SELECT :grantId, kl.id, kl.organization_id, 'USER', :ownerUserId, 'OWNER', :ownerUserId, now(), now()"
              + " FROM knowledge_libraries kl"
              + " WHERE kl.owner_user_id = :ownerUserId AND kl.personal = true"
              + " ON CONFLICT (library_id, subject_user_id) WHERE subject_type = 'USER' DO NOTHING",
      nativeQuery = true)
  void insertOwnerGrantForPersonalLibraryIfAbsent(
      @Param("grantId") UUID grantId, @Param("ownerUserId") UUID ownerUserId);

  /** All grants on a library, used to populate {@link LibraryAccessService}'s per-library cache. */
  List<AssetGrant> findByLibraryId(UUID libraryId);

  /**
   * The locked counterpart of {@link #findByLibraryId}, used only by {@code AssetGrantService}'s
   * last-active-OWNER guard before it revokes or downgrades an OWNER grant (#202 code review nit
   * 2). {@code SELECT ... FOR UPDATE} takes a row lock on every grant of the library for the
   * remainder of the caller's transaction, so two concurrent calls that would each, read in
   * isolation, see the other's OWNER grant as still active and both proceed - the exact race a
   * plain read-then-decide check cannot rule out - instead serialize: the second blocks until the
   * first commits (releasing the lock) or rolls back, and then re-reads the now-current state
   * before making its own decision. Deliberately scoped to only the mutations that touch an active
   * OWNER grant, not every grant write, since this is real row-level database contention, not an
   * in-process lock - see {@code io.opaa.auth.UserService#provisioningLockFor} for why that
   * distinction matters.
   */
  @Query(
      value = "SELECT * FROM asset_grants WHERE library_id = :libraryId FOR UPDATE",
      nativeQuery = true)
  List<AssetGrant> findByLibraryIdForUpdate(@Param("libraryId") UUID libraryId);

  Optional<AssetGrant> findByLibraryIdAndSubjectTypeAndSubjectUserId(
      UUID libraryId, PermissionSubjectType subjectType, UUID subjectUserId);

  Optional<AssetGrant> findByLibraryIdAndSubjectTypeAndSubjectGroupId(
      UUID libraryId, PermissionSubjectType subjectType, UUID subjectGroupId);

  /**
   * Every library id the given user can read via a direct grant, not expired as of {@code now}.
   * Only used as one branch of {@link LibraryAccessService#readableLibraryIds} - deliberately not
   * cached (unlike the per-library grant cache used for single-library checks): the vector search
   * filter must reflect a revoked grant on the very next query (#202 acceptance criteria), and a
   * single indexed query is simpler and safer than a second cache-invalidation path to keep
   * correct.
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
   * GroupService#deleteGroup} to reject deleting a group that still holds a grant (#202 code
   * review: {@code fk_asset_grants_subject_group_organization} is RESTRICT, migration 013, so
   * without this check the delete would surface as an unhandled {@code
   * DataIntegrityViolationException} -> HTTP 500). This is deliberately a second, independent check
   * next to {@code KnowledgeLibraryRepository#existsByOwnerGroupId} (ownership), not a replacement
   * for it - a group can be both the owner of a library and hold a grant on an unrelated one;
   * deleting it must be rejected for either reason.
   */
  boolean existsBySubjectGroupId(UUID subjectGroupId);
}
