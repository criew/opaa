package io.opaa.asset;

import io.opaa.api.types.ExternalAccessState;
import io.opaa.permission.AssetType;
import io.opaa.permission.PermissionHistoryClock;
import io.opaa.permission.PermissionHistoryService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The asset half of the permission history: the {@link AssetVisibilityHistory} intervals, and the
 * Stichtag reconstruction that combines them with the grant intervals {@link
 * PermissionHistoryService} keeps (#238, see
 * docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten). Both halves
 * take their interval boundaries from the one {@link PermissionHistoryClock}, so the interval
 * contract holds across all three tables.
 *
 * <p>An interval records visibility, listed and the release for Fremdzugaenge together. A change of
 * one carries the other forward from the interval it closes, so the shell records reach changes
 * without knowing which type releases for Fremdzugaenge at all.
 *
 * <p>Every recording method runs inside the caller's own transaction (default propagation): a reach
 * change and its history row commit or roll back together.
 */
@Service
public class AssetVisibilityHistoryService {

  private final AssetVisibilityHistoryRepository repository;
  private final PermissionHistoryService permissionHistoryService;
  private final PermissionHistoryClock clock;

  public AssetVisibilityHistoryService(
      AssetVisibilityHistoryRepository repository,
      PermissionHistoryService permissionHistoryService,
      PermissionHistoryClock clock) {
    this.repository = repository;
    this.permissionHistoryService = permissionHistoryService;
    this.clock = clock;
  }

  /** Opens the first interval of a new asset - never released for Fremdzugaenge yet. */
  public void recordCreated(Asset asset, UUID actorUserId) {
    repository.save(
        interval(
            asset,
            ExternalAccessState.NEVER_SET,
            null,
            AssetVisibilityHistoryCause.CREATED,
            actorUserId,
            clock.nextBoundary()));
  }

  /**
   * Closes the open interval and opens a new one with the asset's <i>current</i> visibility and
   * listed - callers apply the change first, and call only when at least one of them differs.
   */
  public void recordVisibilityChanged(Asset asset, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    Optional<AssetVisibilityHistory> previous = closeOpenInterval(asset, now);
    repository.save(
        interval(
            asset,
            previous
                .map(AssetVisibilityHistory::getExternalAccessState)
                .orElse(ExternalAccessState.NEVER_SET),
            previous.map(AssetVisibilityHistory::getExternalAccessExpiresAt).orElse(null),
            AssetVisibilityHistoryCause.VISIBILITY_CHANGED,
            actorUserId,
            now));
  }

  /**
   * Closes the open interval and opens a new one carrying the release state the caller applied.
   * {@code cause} tells a decision ({@link AssetVisibilityHistoryCause#EXTERNAL_ACCESS_CHANGED},
   * with the acting person) from the Befristung running out ({@link
   * AssetVisibilityHistoryCause#EXTERNAL_ACCESS_EXPIRED}, {@code actorUserId} {@code null}).
   */
  public void recordExternalAccessChanged(
      Asset asset,
      ExternalAccessState state,
      Instant expiresAt,
      AssetVisibilityHistoryCause cause,
      UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenInterval(asset, now);
    repository.save(interval(asset, state, expiresAt, cause, actorUserId, now));
  }

  /**
   * Closes the open interval (keeping its own cause) and writes a zero-length marker with {@link
   * AssetVisibilityHistoryCause#ASSET_DELETED} and the actor next to it - the closed interval keeps
   * the cause it was opened with, and the deletion is a fact of its own. Call before the asset is
   * deleted.
   */
  public void recordClosedByAssetDeletion(Asset asset, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    Optional<AssetVisibilityHistory> previous = closeOpenInterval(asset, now);
    AssetVisibilityHistory marker =
        interval(
            asset,
            previous
                .map(AssetVisibilityHistory::getExternalAccessState)
                .orElse(ExternalAccessState.NEVER_SET),
            previous.map(AssetVisibilityHistory::getExternalAccessExpiresAt).orElse(null),
            AssetVisibilityHistoryCause.ASSET_DELETED,
            actorUserId,
            now);
    marker.close(now);
    repository.save(marker);
  }

  /**
   * Every asset id of {@code assetType} {@code userId} could have read at {@code asOf} - the same
   * formula {@code AssetAccessService#readableAssetIds} evaluates for "now", evaluated against the
   * history tables: direct grants, grants to whichever groups the user belonged to at {@code asOf},
   * and every asset that was organization-wide at {@code asOf}. The negative proof ("Z was not
   * among them") holds <b>inside the retention period only</b> - see {@code
   * PermissionHistoryService#readableAssetIdsAsOf}.
   */
  @Transactional(readOnly = true)
  public Set<UUID> readableAssetIdsAsOf(
      AssetType assetType, UUID userId, UUID organizationId, Instant asOf) {
    Set<UUID> readable =
        new HashSet<>(
            permissionHistoryService.readableAssetIdsAsOf(assetType, userId, organizationId, asOf));
    readable.addAll(repository.findOrganizationWideAssetIdsAsOf(assetType, organizationId, asOf));
    return readable;
  }

  /**
   * Every organization-wide interval of one asset overlapping {@code [from, to)} - the third source
   * the Stichtagsauskunft composes (#1822). An organization-wide release reaches every account
   * without naming one, which is why it stays one interval instead of a list of people.
   */
  @Transactional(readOnly = true)
  public List<AssetVisibilityHistory> organizationWideIntervalsBetween(
      AssetType assetType, UUID assetId, UUID organizationId, Instant from, Instant to, int limit) {
    return repository.findOrganizationWideIntervalsOverlapping(
        assetType, assetId, organizationId, from, to, PageRequest.ofSize(limit));
  }

  /**
   * Whether the asset was released for Fremdzugaenge at {@code asOf} (#1731). The Befristung counts
   * at {@code asOf} itself, not at the moment the expiry run wrote it down; {@code false} for an
   * asset no interval covers at that instant - an asset that did not exist was not released.
   */
  @Transactional(readOnly = true)
  public boolean externalAccessActiveAsOf(AssetType assetType, UUID assetId, Instant asOf) {
    return repository
        .findStateAsOf(assetType, assetId, asOf)
        .map(
            interval ->
                interval.getExternalAccessState() == ExternalAccessState.ACTIVE
                    && interval.getExternalAccessExpiresAt() != null
                    && interval.getExternalAccessExpiresAt().isAfter(asOf))
        .orElse(false);
  }

  /**
   * Closes the open interval, if any, and flushes immediately: Hibernate's default flush order runs
   * every queued insert before every queued update, so without this the new row's {@code INSERT}
   * would reach Postgres before the old row's {@code UPDATE ... SET valid_to}, transiently
   * violating the "at most one open interval" unique index.
   */
  private Optional<AssetVisibilityHistory> closeOpenInterval(Asset asset, Instant now) {
    Optional<AssetVisibilityHistory> open =
        repository.findByAssetTypeAndAssetIdAndValidToIsNull(asset.getAssetType(), asset.getId());
    open.ifPresent(
        interval -> {
          interval.close(now);
          repository.saveAndFlush(interval);
        });
    return open;
  }

  private static AssetVisibilityHistory interval(
      Asset asset,
      ExternalAccessState externalAccessState,
      Instant externalAccessExpiresAt,
      AssetVisibilityHistoryCause cause,
      UUID actorUserId,
      Instant validFrom) {
    return new AssetVisibilityHistory(
        asset.getAssetType(),
        asset.getId(),
        asset.getOrganizationId(),
        asset.getVisibility(),
        asset.isListed(),
        externalAccessState,
        externalAccessExpiresAt,
        cause,
        actorUserId,
        validFrom);
  }
}
