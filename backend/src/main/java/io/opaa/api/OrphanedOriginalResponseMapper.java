package io.opaa.api;

import io.opaa.api.dto.OrphanedOriginalDeletionResponse;
import io.opaa.api.dto.OrphanedOriginalReportResponse;
import io.opaa.api.dto.OrphanedOriginalResponse;
import io.opaa.api.dto.SkippedOrphanedOriginalResponse;
import io.opaa.library.OrphanedOriginal;
import io.opaa.library.OrphanedOriginalDeletion;
import io.opaa.library.OrphanedOriginalReport;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the two steps of the orphan cleanup onto their generated API responses (#860: the domain
 * service never sees a DTO). Package-private like every other mapper here.
 */
final class OrphanedOriginalResponseMapper {

  private OrphanedOriginalResponseMapper() {}

  static OrphanedOriginalReportResponse toReportResponse(OrphanedOriginalReport report) {
    List<OrphanedOriginalResponse> orphans = new ArrayList<>();
    for (OrphanedOriginal orphan : report.orphans()) {
      orphans.add(
          new OrphanedOriginalResponse(orphan.locator(), orphan.lastModified(), orphan.size()));
    }
    return new OrphanedOriginalReportResponse(
        orphans,
        report.orphanCount(),
        report.scannedCount(),
        report.withinGracePeriodCount(),
        report.minimumAgeMinutes(),
        report.isTruncated());
  }

  static OrphanedOriginalDeletionResponse toDeletionResponse(OrphanedOriginalDeletion deletion) {
    List<SkippedOrphanedOriginalResponse> skipped = new ArrayList<>();
    for (OrphanedOriginalDeletion.Skipped entry : deletion.skipped()) {
      skipped.add(
          new SkippedOrphanedOriginalResponse(
              entry.locator(),
              io.opaa.api.dto.OrphanedOriginalSkipReason.fromValue(entry.reason().name())));
    }
    return new OrphanedOriginalDeletionResponse(deletion.deleted(), skipped);
  }
}
