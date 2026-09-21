package io.opaa.space;

import io.opaa.permission.PermissionHistorySweeper;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SpaceMembershipHistoryRepository
    extends JpaRepository<SpaceMembershipHistory, UUID>, PermissionHistorySweeper {

  @Override
  default String historyTable() {
    return "space_membership_history";
  }

  /** This table's part of the retention deletion - see {@link PermissionHistorySweeper}. */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from SpaceMembershipHistory h where h.validTo is not null and h.validTo < :cutoff")
  int deleteClosedIntervalsEndingBefore(@Param("cutoff") Instant cutoff);

  Optional<SpaceMembershipHistory> findBySpaceIdAndSubjectUserIdAndValidToIsNull(
      UUID spaceId, UUID subjectUserId);

  Optional<SpaceMembershipHistory> findBySpaceIdAndSubjectGroupIdAndValidToIsNull(
      UUID spaceId, UUID subjectGroupId);

  /** Every open interval of a space - what its deletion has to close (ADR-0016). */
  List<SpaceMembershipHistory> findBySpaceIdAndValidToIsNull(UUID spaceId);

  /**
   * Every space {@code userId} was a member of in their own right at {@code asOf}. Split from the
   * group half below for the same reason {@code PermissionHistoryService#readableAssetIdsAsOf}
   * splits its two: a caller with no groups at that instant must not send an empty {@code IN} list.
   */
  @Query(
      "select h.spaceId from SpaceMembershipHistory h "
          + "where h.organizationId = :organizationId and h.subjectUserId = :userId "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf)")
  Set<UUID> findSpaceIdsByDirectMembershipAsOf(
      @Param("userId") UUID userId,
      @Param("organizationId") UUID organizationId,
      @Param("asOf") Instant asOf);

  /** Every space one of {@code groupIds} was a member of at {@code asOf}. Never called empty. */
  @Query(
      "select h.spaceId from SpaceMembershipHistory h "
          + "where h.organizationId = :organizationId and h.subjectGroupId in :groupIds "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf)")
  Set<UUID> findSpaceIdsByGroupMembershipAsOf(
      @Param("groupIds") Collection<UUID> groupIds,
      @Param("organizationId") UUID organizationId,
      @Param("asOf") Instant asOf);

  /**
   * Test-only cleanup helper - {@code subject_user_id} is {@code ON DELETE RESTRICT}; see {@code
   * io.opaa.permission.AssetGrantHistoryRepository#deleteBySubjectUserIdIn} for the full reasoning
   * and for why {@code @Transactional} is required on a derived delete declared here.
   */
  @Transactional
  void deleteBySubjectUserIdIn(Collection<UUID> subjectUserIds);
}
