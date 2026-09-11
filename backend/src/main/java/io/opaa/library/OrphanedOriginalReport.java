package io.opaa.library;

import java.util.List;

/**
 * The result of one report pass of {@link OrphanedOriginalCleanupService}: the orphans it lists
 * (capped at {@link OrphanedOriginalCleanupService#MAX_LISTED}) and the counts over everything it
 * saw.
 *
 * @param orphans the listed orphans, in the store's own order
 * @param orphanCount how many orphans exist in total, listed or not
 * @param scannedCount how many stored originals of the library were looked at
 * @param withinGracePeriodCount originals no row points to that are still inside the grace period
 * @param minimumAgeMinutes the age threshold this pass applied
 */
public record OrphanedOriginalReport(
    List<OrphanedOriginal> orphans,
    int orphanCount,
    int scannedCount,
    int withinGracePeriodCount,
    int minimumAgeMinutes) {

  public OrphanedOriginalReport {
    orphans = List.copyOf(orphans);
  }

  /** Whether more orphans exist than {@link #orphans} lists - a further pass is needed. */
  public boolean isTruncated() {
    return orphanCount > orphans.size();
  }
}
