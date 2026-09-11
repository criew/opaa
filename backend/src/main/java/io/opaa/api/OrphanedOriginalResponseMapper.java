package io.opaa.api;

import io.opaa.api.dto.OrphanedLibraryReportResponse;
import io.opaa.api.dto.OrphanedLibraryResponse;
import io.opaa.api.dto.OrphanedOriginalDeletionResponse;
import io.opaa.api.dto.OrphanedOriginalReportResponse;
import io.opaa.api.dto.OrphanedOriginalResponse;
import io.opaa.api.dto.SkippedOrphanedOriginalResponse;
import io.opaa.library.OrphanedLibrary;
import io.opaa.library.OrphanedLibraryReport;
import io.opaa.library.OrphanedOriginal;
import io.opaa.library.OrphanedOriginalDeletion;
import io.opaa.library.OrphanedOriginalReport;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the two steps of both orphan cleanup runs onto their generated API responses (#860: the
 * domain service never sees a DTO). Package-private like every other mapper here.
 */
final class OrphanedOriginalResponseMapper {

  private OrphanedOriginalResponseMapper() {}

  static OrphanedOriginalReportResponse toReportResponse(OrphanedOriginalReport report) {
    return new OrphanedOriginalReportResponse(
        toOrphans(report.orphans()),
        report.orphanCount(),
        report.scannedCount(),
        report.referencedCount(),
        report.withinGracePeriodCount(),
        report.minimumAgeMinutes(),
        report.isTruncated());
  }

  static OrphanedLibraryReportResponse toLibraryReportResponse(OrphanedLibraryReport report) {
    List<OrphanedLibraryResponse> libraries = new ArrayList<>();
    for (OrphanedLibrary library : report.libraries()) {
      libraries.add(
          new OrphanedLibraryResponse(
              library.libraryId(),
              toOrphans(library.orphans()),
              library.orphanCount(),
              library.scannedCount(),
              library.withinGracePeriodCount(),
              library.totalSize(),
              library.isTruncated()));
    }
    return new OrphanedLibraryReportResponse(
        libraries,
        report.libraryCount(),
        report.scannedLibraryCount(),
        report.knownLibraryCount(),
        report.minimumAgeMinutes(),
        report.isTruncated());
  }

  private static List<OrphanedOriginalResponse> toOrphans(List<OrphanedOriginal> orphans) {
    List<OrphanedOriginalResponse> responses = new ArrayList<>();
    for (OrphanedOriginal orphan : orphans) {
      responses.add(
          new OrphanedOriginalResponse(orphan.locator(), orphan.lastModified(), orphan.size()));
    }
    return responses;
  }

  static OrphanedOriginalDeletionResponse toDeletionResponse(OrphanedOriginalDeletion deletion) {
    List<SkippedOrphanedOriginalResponse> skipped = new ArrayList<>();
    for (OrphanedOriginalDeletion.Skipped entry : deletion.skipped()) {
      skipped.add(new SkippedOrphanedOriginalResponse(entry.locator(), entry.reason()));
    }
    return new OrphanedOriginalDeletionResponse(deletion.deleted(), skipped);
  }
}
