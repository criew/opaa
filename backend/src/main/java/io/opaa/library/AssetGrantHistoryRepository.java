package io.opaa.library;

import io.opaa.api.types.PermissionSubjectType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AssetGrantHistoryRepository extends JpaRepository<AssetGrantHistory, UUID> {

  Optional<AssetGrantHistory> findByLibraryIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
      UUID libraryId, PermissionSubjectType subjectType, UUID subjectUserId);

  /**
   * Test-only cleanup helper: {@code subject_user_id} is {@code ON DELETE RESTRICT} (migration 018,
   * code review of #238 finding 4 - deliberately, so an account deletion is blocked until a
   * pseudonymisation mechanism exists), so an integration test that both provisions users through a
   * path that historises a grant (any {@code AssetGrantService}/{@code KnowledgeLibraryService}
   * call) and deletes those users again in its own teardown must purge their history rows first.
   * Production code never calls this - there is no account deletion feature yet (#391/#395 own
   * that). {@code @Transactional} is required here (unlike {@link JpaRepository}'s own {@code
   * delete}/{@code save} methods, which {@code SimpleJpaRepository} already wraps by default) -
   * without it, a caller invoking this derived delete method outside an existing transaction hits
   * {@code TransactionRequiredException}: Spring Data only auto-wraps its own base CRUD methods,
   * not custom derived query methods declared on this interface.
   */
  @Transactional
  void deleteBySubjectUserIdIn(Collection<UUID> subjectUserIds);

  Optional<AssetGrantHistory> findByLibraryIdAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
      UUID libraryId, PermissionSubjectType subjectType, UUID subjectGroupId);

  /**
   * Every library a direct grant to {@code userId} covered at {@code asOf}, i.e. the interval's
   * {@code validFrom <= asOf} and ({@code validTo IS NULL OR validTo > asOf}) - and, since {@link
   * AssetGrantHistory#getExpiresAt()} is not itself a bound on {@code validTo}, additionally not
   * already expired at {@code asOf} per the grant's own {@code expiresAt}. Mirrors {@link
   * AssetGrantRepository#findReadableLibraryIdsByDirectGrant}'s "any role counts" semantics -
   * {@code AssetRole.USER} is the floor for read access, so the role itself is not filtered on here
   * either.
   */
  @Query(
      "select h.libraryId from AssetGrantHistory h "
          + "where h.subjectType = io.opaa.api.types.PermissionSubjectType.USER "
          + "and h.subjectUserId = :userId and h.organizationId = :organizationId "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf) "
          + "and (h.expiresAt is null or h.expiresAt > :asOf)")
  Set<UUID> findReadableLibraryIdsByDirectGrantAsOf(
      @Param("userId") UUID userId,
      @Param("organizationId") UUID organizationId,
      @Param("asOf") Instant asOf);

  /** The group-grant counterpart of {@link #findReadableLibraryIdsByDirectGrantAsOf}. */
  @Query(
      "select h.libraryId from AssetGrantHistory h "
          + "where h.subjectType = io.opaa.api.types.PermissionSubjectType.GROUP "
          + "and h.subjectGroupId in :groupIds and h.organizationId = :organizationId "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf) "
          + "and (h.expiresAt is null or h.expiresAt > :asOf)")
  Set<UUID> findReadableLibraryIdsByGroupGrantAsOf(
      @Param("groupIds") Set<UUID> groupIds,
      @Param("organizationId") UUID organizationId,
      @Param("asOf") Instant asOf);
}
