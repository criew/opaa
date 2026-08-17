package io.opaa.library;

import io.opaa.group.PermissionSubjectType;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetGrantHistoryRepository extends JpaRepository<AssetGrantHistory, UUID> {

  Optional<AssetGrantHistory> findByLibraryIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
      UUID libraryId, PermissionSubjectType subjectType, UUID subjectUserId);

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
          + "where h.subjectType = io.opaa.group.PermissionSubjectType.USER "
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
          + "where h.subjectType = io.opaa.group.PermissionSubjectType.GROUP "
          + "and h.subjectGroupId in :groupIds and h.organizationId = :organizationId "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf) "
          + "and (h.expiresAt is null or h.expiresAt > :asOf)")
  Set<UUID> findReadableLibraryIdsByGroupGrantAsOf(
      @Param("groupIds") Set<UUID> groupIds,
      @Param("organizationId") UUID organizationId,
      @Param("asOf") Instant asOf);
}
