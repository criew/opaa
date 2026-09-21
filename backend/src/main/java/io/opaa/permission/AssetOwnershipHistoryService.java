package io.opaa.permission;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Records the ownership history of an asset as half-open intervals (#1815, ADR-0036 Entscheidung
 * 8): "who was responsible for this object on 3 March" stays answerable after the audit log's own
 * retention period has taken the event away.
 *
 * <p>Follows the interval contract of {@link PermissionHistoryService} and shares its {@link
 * PermissionHistoryClock}: successive intervals of the same asset have strictly increasing
 * boundaries, closing one and opening the next share a single boundary value, and the partial
 * unique index {@code uk_asset_ownership_history_open} - not the clock - is what stops two
 * concurrent transactions from leaving an interleaved chain behind.
 *
 * <p>An asset has exactly one owner at a time, so unlike a grant there is no terminal marker row: a
 * transfer closes one interval and opens the next, and the actor of the change is recorded on the
 * interval that opens. Only a deletion leaves a closed interval without a successor.
 *
 * <p><b>Writers.</b> {@code io.opaa.space.SpaceService} for {@code SPACE}; the ownership of a
 * library follows with #1819. Every method runs in the caller's own transaction, so the ownership
 * change and its interval commit or roll back together.
 */
@Service
public class AssetOwnershipHistoryService {

  private final AssetOwnershipHistoryRepository repository;
  private final PermissionHistoryClock clock;

  AssetOwnershipHistoryService(
      AssetOwnershipHistoryRepository repository, PermissionHistoryClock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /** Opens the first interval for a newly created asset. */
  public void recordCreated(
      AssetType assetType, UUID assetId, PermissionSubject owner, UUID actorUserId) {
    repository.save(
        new AssetOwnershipHistory(
            assetType,
            assetId,
            owner.organizationId(),
            owner,
            AssetOwnershipHistoryCause.CREATED,
            actorUserId,
            clock.nextBoundary()));
  }

  /**
   * Closes the open interval and opens the next one for {@code newOwner}. If no open interval is
   * found - an asset created before this table and not covered by the backfill - only the new
   * interval is written; the history is then incomplete for that asset, not broken.
   */
  public void recordTransferred(
      AssetType assetType, UUID assetId, PermissionSubject newOwner, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenInterval(assetType, assetId, now);
    repository.save(
        new AssetOwnershipHistory(
            assetType,
            assetId,
            newOwner.organizationId(),
            newOwner,
            AssetOwnershipHistoryCause.TRANSFERRED,
            actorUserId,
            now));
  }

  /**
   * Closes the open interval of a deleted asset and writes the zero-length marker that records the
   * deletion itself, with its actor. {@code asset_id} carries no foreign key (ADR-0016) - without
   * this call the deleted asset would keep reporting a current owner forever, and without the
   * marker the deletion would be the one ownership event nobody is named for: the closed interval
   * keeps the cause it was opened with, and that cause is somebody else's act. Same mechanics as
   * {@code io.opaa.space.SpaceMembershipHistoryService#recordSpaceDeleted}.
   */
  public void recordAssetDeleted(
      AssetType assetType, UUID assetId, PermissionSubject lastOwner, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenInterval(assetType, assetId, now);
    AssetOwnershipHistory marker =
        new AssetOwnershipHistory(
            assetType,
            assetId,
            lastOwner.organizationId(),
            lastOwner,
            AssetOwnershipHistoryCause.ASSET_DELETED,
            actorUserId,
            now);
    marker.close(now);
    repository.save(marker);
  }

  /**
   * Flushes immediately for the same reason {@code PermissionHistoryService#closeOpenGrantInterval}
   * does: Hibernate orders every queued insert before every queued update, so without the flush the
   * next interval's {@code INSERT} would reach Postgres before this {@code UPDATE ... SET valid_to}
   * and transiently violate {@code uk_asset_ownership_history_open}.
   */
  private void closeOpenInterval(AssetType assetType, UUID assetId, Instant now) {
    repository
        .findByAssetTypeAndAssetIdAndValidToIsNull(assetType, assetId)
        .ifPresent(
            interval -> {
              interval.close(now);
              repository.saveAndFlush(interval);
            });
  }
}
