package io.opaa.library;

import java.util.List;

/**
 * The result of one pass over an organization's whole storage area: the storage areas that belong
 * to no library row any more, each with the originals it still holds.
 *
 * @param libraries the listed storage areas, in the store's own order, capped at {@link
 *     OrphanedOriginalCleanupService#MAX_LISTED}
 * @param libraryCount how many orphaned storage areas holding at least one original exist in total,
 *     listed or not
 * @param scannedLibraryCount how many storage areas the organization has at all
 * @param knownLibraryCount how many of them a library row exists for - the sanity check of the
 *     whole report, as {@link OrphanedOriginalReport#referencedCount()} is for the library-bound
 *     one: storage areas but none of them known means this storage belongs to somebody else
 * @param minimumAgeMinutes the age threshold this pass applied
 */
public record OrphanedLibraryReport(
    List<OrphanedLibrary> libraries,
    int libraryCount,
    int scannedLibraryCount,
    int knownLibraryCount,
    int minimumAgeMinutes) {

  public OrphanedLibraryReport {
    libraries = List.copyOf(libraries);
  }

  /**
   * Whether this pass left something unlisted - more orphaned storage areas than {@link #libraries}
   * names, or a listed one whose orphans are cut short. A further pass after deleting what is
   * listed is needed either way.
   */
  public boolean isTruncated() {
    return libraryCount > libraries.size()
        || libraries.stream().anyMatch(OrphanedLibrary::isTruncated);
  }
}
