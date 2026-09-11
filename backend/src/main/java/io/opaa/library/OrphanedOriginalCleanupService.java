package io.opaa.library;

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
 * Finds and, on explicit request, removes orphaned originals of one library: stored originals in
 * {@link UploadedOriginalStore} that no document row of the library points to (ADR-0030,
 * "Konsequenzen" - a failed delete, a process killed between storing and inserting, a restored
 * database each leave one behind).
 *
 * <p><b>Two steps, never one.</b> {@link #report} changes nothing; {@link #delete} removes only the
 * locators it is handed and re-checks each of them first. <b>Orphaned is what no row points to</b>
 * - whatever the row's status, source type or parent: a {@code PENDING} or {@code FAILED} row keeps
 * its original, and an attachment row's synthetic path can never match a stored locator, so
 * including every row costs nothing and filtering would be an assumption about the future. <b>An
 * original inside the grace period is never touched</b>: an upload whose row is being written this
 * moment is not an orphan. The known-locator set is bounded by the library's rows; the store is
 * walked one original at a time, never held as a whole.
 */
@Service
public class OrphanedOriginalCleanupService {

  /** Orphans listed per report and locators accepted per delete call. */
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
    int graceMinutes = uploadProperties.orphanGraceMinutes();
    if (minimumAgeMinutes != null && minimumAgeMinutes < graceMinutes) {
      throw new IllegalArgumentException(
          "minimumAgeMinutes darf die konfigurierte Schonfrist von "
              + graceMinutes
              + " Minuten nicht unterschreiten, war "
              + minimumAgeMinutes);
    }
    int appliedMinutes = minimumAgeMinutes == null ? graceMinutes : minimumAgeMinutes;
    Instant threshold = clock.instant().minus(Duration.ofMinutes(appliedMinutes));
    Set<String> known = new HashSet<>(documentRepository.findFilePathsByLibraryId(libraryId));
    List<OrphanedOriginal> listed = new ArrayList<>();
    int[] counts = new int[3]; // scanned, orphans, within grace period
    store.forEachStoredOriginal(
        libraryId,
        original -> {
          counts[0]++;
          if (known.contains(original.locator())) {
            return;
          }
          if (original.lastModified().isAfter(threshold)) {
            counts[2]++;
            return;
          }
          counts[1]++;
          if (listed.size() < MAX_LISTED) {
            listed.add(
                new OrphanedOriginal(original.locator(), original.lastModified(), original.size()));
          }
        });
    OrphanedOriginalReport report =
        new OrphanedOriginalReport(listed, counts[1], counts[0], counts[2], appliedMinutes);
    log.info(
        "Orphan report for library {}: {} stored original(s) scanned, {} orphaned (older than {}"
            + " minute(s)), {} within the grace period{}",
        libraryId,
        report.scannedCount(),
        report.orphanCount(),
        appliedMinutes,
        report.withinGracePeriodCount(),
        report.isTruncated() ? " - listing truncated at " + MAX_LISTED : "");
    return report;
  }

  /**
   * Removes the originals behind {@code locators} from {@code libraryId}'s storage area - and only
   * those. Every locator is checked again right before it goes: one a row points to by now, one
   * still inside the configured grace period and one the store does not hold for this library are
   * skipped with their reason. A removal the store refused is reported as such, not as done.
   *
   * @throws IllegalArgumentException when no or more than {@link #MAX_LISTED} locators are given
   * @throws UploadStoreUnavailableException when the store cannot be reached
   */
  public OrphanedOriginalDeletion delete(
      UUID organizationId, UUID libraryId, List<String> locators) {
    requireLibrary(organizationId, libraryId);
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
    Instant threshold =
        clock.instant().minus(Duration.ofMinutes(uploadProperties.orphanGraceMinutes()));
    Set<String> known = new HashSet<>(documentRepository.findFilePathsByLibraryId(libraryId));
    Map<String, UploadedOriginalStore.StoredOriginal> inStore = new HashMap<>();
    store.forEachStoredOriginal(
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
      } else {
        UploadedOriginalRef ref = new UploadedOriginalRef(libraryId, locator);
        store.delete(ref);
        if (store.belongsToLibrary(ref)) {
          skipped.add(skip(locator, OrphanedOriginalSkipReason.DELETE_FAILED));
        } else {
          deleted.add(locator);
        }
      }
    }
    log.info(
        "Orphan cleanup for library {}: {} original(s) removed, {} skipped",
        libraryId,
        deleted.size(),
        skipped.size());
    return new OrphanedOriginalDeletion(deleted, skipped);
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
