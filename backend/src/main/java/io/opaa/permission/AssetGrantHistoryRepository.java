package io.opaa.permission;

import io.opaa.api.types.PermissionSubjectType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AssetGrantHistoryRepository
    extends JpaRepository<AssetGrantHistory, UUID>, PermissionHistorySweeper {

  @Override
  default String historyTable() {
    return "asset_grant_history";
  }

  /**
   * This table's part of the retention deletion - see {@link PermissionHistorySweeper} for the
   * contract. Zero-length event markers ({@code validTo = validFrom}) are closed intervals and are
   * removed with the state intervals around them: they record a revocation whose own history is
   * gone by then.
   */
  @Override
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from AssetGrantHistory h where h.validTo is not null and h.validTo < :cutoff")
  int deleteClosedIntervalsEndingBefore(@Param("cutoff") Instant cutoff);

  Optional<AssetGrantHistory>
      findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectUserIdAndValidToIsNull(
          AssetType assetType, UUID assetId, PermissionSubjectType subjectType, UUID subjectUserId);

  Optional<AssetGrantHistory>
      findByAssetTypeAndAssetIdAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
          AssetType assetType,
          UUID assetId,
          PermissionSubjectType subjectType,
          UUID subjectGroupId);

  /**
   * Test-only cleanup helper: {@code subject_user_id} is {@code ON DELETE RESTRICT} (deliberately,
   * so an account deletion is blocked until a pseudonymisation mechanism exists), so an integration
   * test that both provisions users through a path that historises a grant and deletes those users
   * again in its own teardown must purge their history rows first. Production code never calls this
   * - there is no account deletion feature yet (#391/#395 own that). {@code @Transactional} is
   * required here (unlike {@link JpaRepository}'s own {@code delete}/{@code save} methods, which
   * {@code SimpleJpaRepository} already wraps by default) - without it, a caller invoking this
   * derived delete method outside an existing transaction hits {@code
   * TransactionRequiredException}: Spring Data only auto-wraps its own base CRUD methods, not
   * custom derived query methods declared on this interface.
   */
  @Transactional
  void deleteBySubjectUserIdIn(Collection<UUID> subjectUserIds);

  /**
   * Every asset of {@code assetType} a direct grant to {@code userId} covered at {@code asOf}, i.e.
   * the interval's {@code validFrom <= asOf} and ({@code validTo IS NULL OR validTo > asOf}) - and,
   * since {@link AssetGrantHistory#getExpiresAt()} is not itself a bound on {@code validTo},
   * additionally not already expired at {@code asOf} per the grant's own {@code expiresAt}. Mirrors
   * {@link AssetGrantRepository#findReadableAssetIdsByDirectGrant}'s "any role counts" semantics -
   * {@link io.opaa.api.types.AssetRole#VIEWER} is the floor for read access, so the role itself is
   * not filtered on here either.
   */
  @Query(
      "select h.assetId from AssetGrantHistory h "
          + "where h.assetType = :assetType "
          + "and h.subjectType = io.opaa.api.types.PermissionSubjectType.USER "
          + "and h.subjectUserId = :userId and h.organizationId = :organizationId "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf) "
          + "and (h.expiresAt is null or h.expiresAt > :asOf)")
  Set<UUID> findReadableAssetIdsByDirectGrantAsOf(
      @Param("assetType") AssetType assetType,
      @Param("userId") UUID userId,
      @Param("organizationId") UUID organizationId,
      @Param("asOf") Instant asOf);

  /**
   * Every <i>state</i> interval on one asset that overlaps {@code [from, to)} - the object-entry
   * half of the Stichtagsauskunft (#1822). The zero-length event markers are excluded by {@code
   * validTo > validFrom}: they record a revocation, and the state interval they belong to is in the
   * result already. The grant's own {@code expiresAt} is <b>not</b> applied here; the caller cuts
   * each interval to it, because an expiry ends a state without closing its row.
   */
  @Query(
      "select h from AssetGrantHistory h "
          + "where h.assetType = :assetType and h.assetId = :assetId "
          + "and h.organizationId = :organizationId "
          + "and h.validFrom < :to and (h.validTo is null or h.validTo > :from) "
          + "and (h.validTo is null or h.validTo > h.validFrom)")
  List<AssetGrantHistory> findAssetIntervalsOverlapping(
      @Param("assetType") AssetType assetType,
      @Param("assetId") UUID assetId,
      @Param("organizationId") UUID organizationId,
      @Param("from") Instant from,
      @Param("to") Instant to,
      Pageable page);

  /** The group-grant counterpart of {@link #findReadableAssetIdsByDirectGrantAsOf}. */
  @Query(
      "select h.assetId from AssetGrantHistory h "
          + "where h.assetType = :assetType "
          + "and h.subjectType = io.opaa.api.types.PermissionSubjectType.GROUP "
          + "and h.subjectGroupId in :groupIds and h.organizationId = :organizationId "
          + "and h.validFrom <= :asOf and (h.validTo is null or h.validTo > :asOf) "
          + "and (h.expiresAt is null or h.expiresAt > :asOf)")
  Set<UUID> findReadableAssetIdsByGroupGrantAsOf(
      @Param("assetType") AssetType assetType,
      @Param("groupIds") Set<UUID> groupIds,
      @Param("organizationId") UUID organizationId,
      @Param("asOf") Instant asOf);
}
