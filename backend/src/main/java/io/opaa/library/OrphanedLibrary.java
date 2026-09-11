package io.opaa.library;

import java.util.List;
import java.util.UUID;

/**
 * One storage area no library row belongs to any more, with the orphaned originals it still holds.
 * Every stored original here is orphaned by definition - there is no row that could point to one -
 * so the only thing that keeps one out of {@link #orphans()} is the grace period.
 *
 * @param orphans the listed orphans, capped across the whole report
 * @param orphanCount how many orphans this storage area holds in total, listed or not
 * @param scannedCount how many stored originals were looked at
 * @param withinGracePeriodCount how many of them are still too young to be removed
 * @param totalSize the bytes of the orphans, listed or not - what removing them frees
 */
public record OrphanedLibrary(
    UUID libraryId,
    List<OrphanedOriginal> orphans,
    int orphanCount,
    int scannedCount,
    int withinGracePeriodCount,
    long totalSize) {

  public OrphanedLibrary {
    orphans = List.copyOf(orphans);
  }

  /** Whether more orphans exist here than {@link #orphans} lists. */
  public boolean isTruncated() {
    return orphanCount > orphans.size();
  }
}
