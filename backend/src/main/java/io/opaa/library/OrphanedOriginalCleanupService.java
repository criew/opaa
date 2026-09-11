package io.opaa.library;

import io.opaa.api.types.OrphanedOriginalSkipReason;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.document.DocumentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Finds and, on explicit request, removes orphaned originals in {@link UploadedOriginalStore}
 * (ADR-0030, "Konsequenzen" - a failed delete, a process killed between storing and inserting, a
 * restored database each leave one behind). Two runs over the same machinery: one <b>inside a
 * library</b>, where an original is orphaned when no document row of that library points to it, and
 * one <b>over an organization's whole storage area</b>, which finds the storage areas whose library
 * row is gone and which no library-bound run could ever reach.
 *
 * <p><b>Two steps, never one.</b> Reporting changes nothing; deleting removes only the locators it
 * is handed and checks each of them once more against a fresh reading of rows and store.
 * <b>Orphaned is what no row points to</b> - whatever the row's status, source type or parent: a
 * {@code PENDING} or {@code FAILED} row keeps its original, and an attachment row's synthetic path
 * can never match a stored locator, so including every row costs nothing and filtering would be an
 * assumption about the future. <b>An original inside the grace period is never touched</b>: an
 * upload whose row is being written this moment is not an orphan. The known-locator set is bounded
 * by the library's rows; the store is walked one original at a time, never held as a whole.
 *
 * <p><b>The organization is the boundary of both runs</b>, and the store draws it: every locator is
 * resolved against the storage area of the caller's own organization and the named library, so an
 * original of another organization is "not there" rather than deletable. The storage-bound run
 * additionally leaves alone every storage area a library row exists for - <em>any</em> row, not
 * only one of the caller's organization.
 */
@Service
public class OrphanedOriginalCleanupService {

  /**
   * Orphans listed per report, storage areas listed per storage-bound report, and locators accepted
   * per delete call.
   */
  public static final int MAX_LISTED = 500;

  private static final Logger log = LoggerFactory.getLogger(OrphanedOriginalCleanupService.class);

  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentRepository documentRepository;
  private final UploadedOriginalStore store;
  private final UploadProperties uploadProperties;
  private final Clock clock;

  public OrphanedOriginalCleanupService(
      KnowledgeLibraryRepository libraryRepository,
      DocumentRepository documentRepository,
      UploadedOriginalStore store,
      UploadProperties uploadProperties,
      Clock clock) {
    this.libraryRepository = libraryRepository;
    this.documentRepository = documentRepository;
    this.store = store;
    this.uploadProperties = uploadProperties;
    this.clock = clock;
  }

  /**
   * Lists the orphaned originals of {@code libraryId}, which must belong to {@code organizationId}
   * (a foreign library is absent, not forbidden: 404). {@code minimumAgeMinutes} may raise the
   * configured grace period, never lower it; {@code null} applies the configured one. At most
   * {@link #MAX_LISTED} orphans are listed, all are counted.
   *
   * @throws IllegalArgumentException when {@code minimumAgeMinutes} undercuts the grace period
   * @throws UploadStoreUnavailableException when the store cannot be listed right now
   */
  public OrphanedOriginalReport report(
      UUID organizationId, UUID libraryId, Integer minimumAgeMinutes) {
    requireLibrary(organizationId, libraryId);
    int appliedMinutes = applicableMinutes(minimumAgeMinutes);
    Set<String> known = new HashSet<>(documentRepository.findFilePathsByLibraryId(libraryId));
    Scan scan = scan(organizationId, libraryId, known, threshold(appliedMinutes), MAX_LISTED);
    OrphanedOriginalReport report =
        new OrphanedOriginalReport(
            scan.listed(),
            scan.orphanCount(),
            scan.scannedCount(),
            scan.referencedCount(),
            scan.withinGracePeriodCount(),
            appliedMinutes);
    log.info(
        "Orphan report for library {}: {} stored original(s) scanned, {} referenced by a row, {}"
            + " orphaned (older than {} minute(s)), {} within the grace period{}",
        libraryId,
        report.scannedCount(),
        report.referencedCount(),
        report.orphanCount(),
        appliedMinutes,
        report.withinGracePeriodCount(),
        report.isTruncated() ? " - listing truncated at " + MAX_LISTED : "");
    return report;
  }

  /**
   * Lists the storage areas of {@code organizationId} that no library row belongs to any more, each
   * with the orphaned originals it still holds - the one way to an original whose library was
   * deleted after its removal had failed, which {@link #report} cannot reach because it needs the
   * library row it no longer has.
   *
   * <p>Every stored original of such an area is orphaned by definition; only the grace period keeps
   * one out, so an area whose library was just deleted shows its originals under {@code
   * withinGracePeriodCount} and becomes deletable one grace period later. <b>An area a library row
   * exists for is never reported</b>, and neither is one holding no original at all - an emptied
   * directory is nothing this run could remove. At most {@link #MAX_LISTED} areas and {@link
   * #MAX_LISTED} orphans across all of them are listed; all are counted.
   *
   * @throws IllegalArgumentException when {@code minimumAgeMinutes} undercuts the grace period
   * @throws UploadStoreUnavailableException when the store cannot be listed right now
   */
  public OrphanedLibraryReport reportOrphanedLibraries(
      UUID organizationId, Integer minimumAgeMinutes) {
    int appliedMinutes = applicableMinutes(minimumAgeMinutes);
    Instant threshold = threshold(appliedMinutes);
    Set<UUID> ownLibraries =
        new HashSet<>(libraryRepository.findIdsByOrganizationId(organizationId));
    List<OrphanedLibrary> listed = new ArrayList<>();
    int[] counts = new int[4]; // scanned areas, known areas, orphaned areas, orphans listed
    store.forEachStoredLibrary(
        organizationId,
        libraryId -> {
          counts[0]++;
          // Any row anywhere protects the area, not only one of this organization: an area whose
          // library belongs elsewhere is misfiled, and deleting it would cross the boundary this
          // run exists to respect.
          if (ownLibraries.contains(libraryId) || libraryRepository.existsById(libraryId)) {
            counts[1]++;
            return;
          }
          boolean listable = listed.size() < MAX_LISTED;
          Scan scan =
              scan(
                  organizationId,
                  libraryId,
                  Set.of(),
                  threshold,
                  listable ? MAX_LISTED - counts[3] : 0);
          if (scan.scannedCount() == 0) {
            return;
          }
          counts[2]++;
          if (listable) {
            counts[3] += scan.listed().size();
            listed.add(
                new OrphanedLibrary(
                    libraryId,
                    scan.listed(),
                    scan.orphanCount(),
                    scan.scannedCount(),
                    scan.withinGracePeriodCount(),
                    scan.orphanSize()));
          }
        });
    OrphanedLibraryReport report =
        new OrphanedLibraryReport(listed, counts[2], counts[0], counts[1], appliedMinutes);
    log.info(
        "Orphan report for organization {}: {} storage area(s) scanned, {} with a library row, {}"
            + " orphaned and holding at least one original (older than {} minute(s)){}",
        organizationId,
        report.scannedLibraryCount(),
        report.knownLibraryCount(),
        report.libraryCount(),
        appliedMinutes,
        report.isTruncated() ? " - listing truncated at " + MAX_LISTED : "");
    return report;
  }

  /**
   * Removes the originals behind {@code locators} from {@code libraryId}'s storage area - and only
   * those. Rows and store are read once at the start of this call, and every locator is checked
   * against that reading before it goes: one a row points to, one still inside the configured grace
   * period and one the store does not hold for this library are skipped with their reason.
   *
   * <p><b>A removal that is not confirmed ends that locator, not the call.</b> A store that refuses
   * or becomes unreachable mid-call makes the remaining locators {@code DELETE_FAILED} and the
   * result still names every original that actually went - the bytes are gone for good, so the one
   * record of which ones must survive the failure that happened next.
   *
   * @throws IllegalArgumentException when no or more than {@link #MAX_LISTED} locators are given
   * @throws UploadStoreUnavailableException when the store cannot be read at all, before anything
   *     has been removed
   */
  public OrphanedOriginalDeletion delete(
      UUID organizationId, UUID libraryId, List<String> locators) {
    requireLibrary(organizationId, libraryId);
    Set<String> requested = requested(locators);
    Set<String> known = new HashSet<>(documentRepository.findFilePathsByLibraryId(libraryId));
    return remove(organizationId, libraryId, requested, known);
  }

  /**
   * The second step of the storage-bound run: removes the named originals from the storage area of
   * {@code libraryId} inside {@code organizationId}, which must be the area of a library that no
   * longer exists. A library that does exist is rejected outright rather than handled here - its
   * originals are the business of {@link #delete}, which holds them against their rows.
   *
   * <p>Nothing else changes: the same grace period, the same per-locator checks, and the same
   * containment - a locator outside the caller's own organization or outside {@code libraryId} is
   * {@code NOT_IN_STORE}, because the store resolves it against exactly that area.
   *
   * @throws IllegalArgumentException when the library still exists, or when no or more than {@link
   *     #MAX_LISTED} locators are given
   * @throws UploadStoreUnavailableException when the store cannot be read at all, before anything
   *     has been removed
   */
  public OrphanedOriginalDeletion deleteInOrphanedLibrary(
      UUID organizationId, UUID libraryId, List<String> locators) {
    if (libraryRepository.existsById(libraryId)) {
      throw new IllegalArgumentException(
          "Die Bibliothek "
              + libraryId
              + " existiert - ihre verwaisten Originale entfernt der bibliotheksbezogene"
              + " Aufräumlauf");
    }
    // No row can name a locator of a library that does not exist: documents.library_id is a
    // foreign key, so the set of protecting rows is empty rather than merely unread.
    return remove(organizationId, libraryId, requested(locators), Set.of());
  }

  /**
   * The locators to work on, blanks and duplicates removed.
   *
   * @throws IllegalArgumentException when none or more than {@link #MAX_LISTED} remain
   */
  private static Set<String> requested(List<String> locators) {
    Set<String> requested = new LinkedHashSet<>();
    for (String locator : locators) {
      if (locator != null && !locator.isBlank()) {
        requested.add(locator);
      }
    }
    if (requested.isEmpty()) {
      throw new IllegalArgumentException("locators darf nicht leer sein");
    }
    if (requested.size() > MAX_LISTED) {
      throw new IllegalArgumentException(
          "locators darf höchstens " + MAX_LISTED + " Einträge enthalten, war " + requested.size());
    }
    return requested;
  }

  /**
   * The one removal path of both runs. {@code known} is what a row protects; the grace period is
   * always the configured one, never a raised one from a report - the report may look further back
   * than it deletes, not the other way round.
   */
  private OrphanedOriginalDeletion remove(
      UUID organizationId, UUID libraryId, Set<String> requested, Set<String> known) {
    Instant threshold = threshold(uploadProperties.orphanGraceMinutes());
    Map<String, UploadedOriginalStore.StoredOriginal> inStore = new HashMap<>();
    store.forEachStoredOriginal(
        organizationId,
        libraryId,
        original -> {
          if (requested.contains(original.locator())) {
            inStore.put(original.locator(), original);
          }
        });
    List<String> deleted = new ArrayList<>();
    List<OrphanedOriginalDeletion.Skipped> skipped = new ArrayList<>();
    for (String locator : requested) {
      UploadedOriginalStore.StoredOriginal original = inStore.get(locator);
      // Referenced first, whether or not the store still holds it: that a row points to this
      // locator is the one fact the caller has to see - a stale report, or the wrong list pasted.
      if (known.contains(locator)) {
        skipped.add(skip(locator, OrphanedOriginalSkipReason.REFERENCED));
      } else if (original == null) {
        skipped.add(skip(locator, OrphanedOriginalSkipReason.NOT_IN_STORE));
      } else if (original.lastModified().isAfter(threshold)) {
        skipped.add(skip(locator, OrphanedOriginalSkipReason.WITHIN_GRACE_PERIOD));
      } else if (removed(organizationId, libraryId, locator)) {
        deleted.add(locator);
      } else {
        skipped.add(skip(locator, OrphanedOriginalSkipReason.DELETE_FAILED));
      }
    }
    log.info(
        "Orphan cleanup for library {}: {} original(s) removed, {} skipped",
        libraryId,
        deleted.size(),
        skipped.size());
    return new OrphanedOriginalDeletion(deleted, skipped);
  }

  /**
   * One walk over the storage area of {@code libraryId}, counting everything it sees and listing at
   * most {@code listBudget} orphans - the shared body of both reports. {@code known} names the
   * locators a row protects; for a library that no longer exists it is empty, and then every stored
   * original past the grace period is an orphan.
   */
  private Scan scan(
      UUID organizationId, UUID libraryId, Set<String> known, Instant threshold, int listBudget) {
    List<OrphanedOriginal> listed = new ArrayList<>();
    int[] counts = new int[4]; // scanned, orphans, within grace period, referenced
    long[] orphanSize = new long[1];
    store.forEachStoredOriginal(
        organizationId,
        libraryId,
        original -> {
          counts[0]++;
          if (known.contains(original.locator())) {
            counts[3]++;
            return;
          }
          if (original.lastModified().isAfter(threshold)) {
            counts[2]++;
            return;
          }
          counts[1]++;
          orphanSize[0] += original.size();
          if (listed.size() < listBudget) {
            listed.add(
                new OrphanedOriginal(original.locator(), original.lastModified(), original.size()));
          }
        });
    return new Scan(listed, counts[1], counts[0], counts[3], counts[2], orphanSize[0]);
  }

  /** What one walk over a storage area saw. */
  private record Scan(
      List<OrphanedOriginal> listed,
      int orphanCount,
      int scannedCount,
      int referencedCount,
      int withinGracePeriodCount,
      long orphanSize) {}

  /**
   * The age threshold a report applies: the configured grace period, raised by {@code
   * minimumAgeMinutes} if it names a larger one.
   *
   * @throws IllegalArgumentException when it names a smaller one
   */
  private int applicableMinutes(Integer minimumAgeMinutes) {
    int graceMinutes = uploadProperties.orphanGraceMinutes();
    if (minimumAgeMinutes != null && minimumAgeMinutes < graceMinutes) {
      throw new IllegalArgumentException(
          "minimumAgeMinutes darf die konfigurierte Schonfrist von "
              + graceMinutes
              + " Minuten nicht unterschreiten, war "
              + minimumAgeMinutes);
    }
    return minimumAgeMinutes == null ? graceMinutes : minimumAgeMinutes;
  }

  private Instant threshold(int minutes) {
    return clock.instant().minus(Duration.ofMinutes(minutes));
  }

  /**
   * Whether the original behind {@code locator} is confirmed gone: {@link
   * UploadedOriginalStore#delete} swallows a refused removal, so only the check afterwards settles
   * it. An unreachable store answers that check with an exception - caught here, because one
   * locator's failure must not cost the caller the record of the ones already removed.
   */
  private boolean removed(UUID organizationId, UUID libraryId, String locator) {
    UploadedOriginalRef ref = new UploadedOriginalRef(organizationId, libraryId, locator);
    try {
      store.delete(ref);
      return !store.belongsToLibrary(ref);
    } catch (UploadStoreUnavailableException e) {
      log.warn(
          "Upload store did not confirm the removal of {} in library {}; reported as failed",
          locator,
          libraryId);
      return false;
    }
  }

  private static OrphanedOriginalDeletion.Skipped skip(
      String locator, OrphanedOriginalSkipReason reason) {
    return new OrphanedOriginalDeletion.Skipped(locator, reason);
  }

  private void requireLibrary(UUID organizationId, UUID libraryId) {
    libraryRepository
        .findById(libraryId)
        .filter(candidate -> organizationId.equals(candidate.getOrganizationId()))
        .orElseThrow(() -> new NotFoundException("Bibliothek nicht gefunden"));
  }
}
