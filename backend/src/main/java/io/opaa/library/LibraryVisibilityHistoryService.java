package io.opaa.library;

import io.opaa.api.types.ExternalAccessState;
import io.opaa.permission.PermissionHistoryClock;
import io.opaa.permission.PermissionHistoryService;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library half of the permission history: the {@link LibraryVisibilityHistory} intervals, and
 * the Stichtag reconstruction that combines them with the grant intervals {@link
 * PermissionHistoryService} keeps (#238, see
 * docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten).
 *
 * <p><b>Why the split runs here</b> (ADR-0036, Entscheidung 12): a library's visibility and its
 * release for Fremdzugaenge are library state, not grants - the permission model must not know
 * either. The composition mirrors the live path exactly: {@link LibraryAccessService} composes the
 * grant formula with the organization-wide floor for "now", this class composes the grant history
 * with the organization-wide history for any past instant. Both halves take their interval
 * boundaries from the one {@link PermissionHistoryClock}, so the interval contract holds across all
 * three tables.
 *
 * <p>Every recording method runs inside the caller's own transaction (default propagation): a
 * visibility change and its history row commit or roll back together.
 */
@Service
public class LibraryVisibilityHistoryService {

  private final LibraryVisibilityHistoryRepository visibilityHistoryRepository;
  private final PermissionHistoryService permissionHistoryService;
  private final PermissionHistoryClock clock;

  LibraryVisibilityHistoryService(
      LibraryVisibilityHistoryRepository visibilityHistoryRepository,
      PermissionHistoryService permissionHistoryService,
      PermissionHistoryClock clock) {
    this.visibilityHistoryRepository = visibilityHistoryRepository;
    this.permissionHistoryService = permissionHistoryService;
    this.clock = clock;
  }

  public void recordLibraryCreated(KnowledgeLibrary library, UUID actorUserId) {
    visibilityHistoryRepository.save(
        intervalFor(
            library, LibraryVisibilityHistoryCause.CREATED, actorUserId, clock.nextBoundary()));
  }

  /**
   * Closes the currently open interval for {@code library} and opens a new one with its *current*
   * visibility/listed - callers apply the change to the entity first. A no-op if visibility and
   * listed are unchanged; call only when at least one actually differs.
   */
  public void recordVisibilityChanged(KnowledgeLibrary library, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenInterval(library.getId(), now);
    visibilityHistoryRepository.save(
        intervalFor(library, LibraryVisibilityHistoryCause.VISIBILITY_CHANGED, actorUserId, now));
  }

  /**
   * Closes the currently open interval for {@code library} (keeping its own recorded cause
   * unchanged) and additionally writes a zero-length {@link LibraryVisibilityHistory#terminal}
   * marker with {@link LibraryVisibilityHistoryCause#LIBRARY_DELETED} - see that factory's Javadoc
   * for why the closing needs its own row. Call before the library itself is deleted: {@code
   * library_id} carries no foreign key, so a library deletion never closes this interval on its
   * own, leaving a deleted library's visibility looking still in effect.
   */
  public void recordVisibilityClosedByLibraryDeletion(KnowledgeLibrary library, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenInterval(library.getId(), now);
    visibilityHistoryRepository.save(
        LibraryVisibilityHistory.terminal(
            library, LibraryVisibilityHistoryCause.LIBRARY_DELETED, actorUserId, now));
  }

  /**
   * Closes the currently open interval for {@code library} and opens a new one carrying its
   * *current* release state - callers apply the change to the entity first. {@code cause}
   * distinguishes a decision ({@link LibraryVisibilityHistoryCause#EXTERNAL_ACCESS_CHANGED}, with
   * the acting person) from the Befristung running out ({@link
   * LibraryVisibilityHistoryCause#EXTERNAL_ACCESS_EXPIRED}, with {@code actorUserId} {@code null} -
   * nobody acted). Same closing mechanics as {@link #recordVisibilityChanged}, which is the point:
   * the release lives in the same interval as visibility/listed, so a reconstruction at any
   * Stichtag answers all three questions from one row.
   */
  public void recordExternalAccessChanged(
      KnowledgeLibrary library, LibraryVisibilityHistoryCause cause, UUID actorUserId) {
    Instant now = clock.nextBoundary();
    closeOpenInterval(library.getId(), now);
    visibilityHistoryRepository.save(intervalFor(library, cause, actorUserId, now));
  }

  /**
   * Every library id {@code userId} could have read at {@code asOf} - the same formula {@link
   * LibraryAccessService#readableLibraryIds} evaluates for "now", evaluated here against the
   * history tables for any past instant: direct grants, grants to whichever groups the user
   * belonged to at {@code asOf}, and every library that was organization-wide at {@code asOf}.
   * Answers both #238's acceptance criteria directly - "which libraries could this person read on
   * day X" is this method's return value, and the negative question "prove library Z was not among
   * them" is answered by checking its absence in that same, single reconstruction rather than by
   * the absence of a log entry, which the feature spec explicitly rejects as unprovable.
   */
  @Transactional(readOnly = true)
  public Set<UUID> readableLibraryIdsAsOf(UUID userId, UUID organizationId, Instant asOf) {
    Set<UUID> readable =
        new HashSet<>(
            permissionHistoryService.readableAssetIdsAsOf(
                KnowledgeLibrary.ASSET_TYPE, userId, organizationId, asOf));
    readable.addAll(
        visibilityHistoryRepository.findOrganizationWideLibraryIdsAsOf(organizationId, asOf));
    return readable;
  }

  /**
   * Whether {@code libraryId} was released for Fremdzugaenge at {@code asOf} - the Stichtag
   * counterpart of {@link KnowledgeLibrary#isExternalAccessActive(Instant)} (#1731). Answers the
   * audit question "was this Bestand reachable from outside the house in 2026" from the history
   * alone, which is the point of historising the field rather than only logging it: the log is
   * deleted monthwise after its retention, the interval is not.
   *
   * <p>The Befristung counts at {@code asOf} itself, not at the moment {@link
   * LibraryExternalAccessExpiryService} wrote the expiry down: an interval that still reads {@code
   * ACTIVE} because the run had not come round yet was, at an instant past its own {@code
   * expiresAt}, not in effect. {@code false} for a library no interval covers at that instant - a
   * library that did not exist was not released.
   */
  @Transactional(readOnly = true)
  public boolean externalAccessActiveAsOf(UUID libraryId, Instant asOf) {
    return visibilityHistoryRepository
        .findStateAsOf(libraryId, asOf)
        .map(
            interval ->
                interval.getExternalAccessState() == ExternalAccessState.ACTIVE
                    && interval.getExternalAccessExpiresAt() != null
                    && interval.getExternalAccessExpiresAt().isAfter(asOf))
        .orElse(false);
  }

  /**
   * Closes the open interval, if any, and flushes immediately - not left to the transaction's
   * normal flush at commit. Hibernate's default flush order runs every queued insert before every
   * queued update, so without this explicit {@code saveAndFlush}, closing the old interval and
   * opening the new one in the same transaction would send the new row's {@code INSERT} to Postgres
   * before the old row's {@code UPDATE ... SET valid_to}, transiently violating the "at most one
   * open interval" unique index.
   */
  private void closeOpenInterval(UUID libraryId, Instant now) {
    visibilityHistoryRepository
        .findByLibraryIdAndValidToIsNull(libraryId)
        .ifPresent(
            interval -> {
              interval.close(now);
              visibilityHistoryRepository.saveAndFlush(interval);
            });
  }

  private static LibraryVisibilityHistory intervalFor(
      KnowledgeLibrary library,
      LibraryVisibilityHistoryCause cause,
      UUID actorUserId,
      Instant validFrom) {
    return new LibraryVisibilityHistory(
        library.getId(),
        library.getOrganizationId(),
        library.getVisibility(),
        library.isListed(),
        library.getExternalAccessState(),
        library.getExternalAccessExpiresAt(),
        cause,
        actorUserId,
        validFrom);
  }
}
