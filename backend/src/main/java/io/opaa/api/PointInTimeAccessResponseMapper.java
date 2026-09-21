package io.opaa.api;

import io.opaa.api.dto.AccessAsOfEntry;
import io.opaa.api.dto.AccessAsOfPage;
import io.opaa.revision.AccessAsOfResult;
import java.util.List;

/** Maps the Stichtagsauskunft onto its generated response (#1822, ADR-0006). */
final class PointInTimeAccessResponseMapper {

  private PointInTimeAccessResponseMapper() {}

  static AccessAsOfPage toPage(AccessAsOfResult result) {
    return new AccessAsOfPage(
            result.objectType(),
            result.objectId(),
            result.from(),
            result.to(),
            result.beyondRetention(),
            result.sourcesNotCovered(),
            toEntries(result.entries()),
            result.page(),
            result.size(),
            result.totalElements(),
            result.totalPages())
        .objectName(result.objectName())
        .retentionCutoff(result.retentionCutoff());
  }

  private static List<AccessAsOfEntry> toEntries(List<io.opaa.revision.AccessAsOfEntry> entries) {
    return entries.stream().map(PointInTimeAccessResponseMapper::toEntry).toList();
  }

  private static AccessAsOfEntry toEntry(io.opaa.revision.AccessAsOfEntry entry) {
    return new AccessAsOfEntry(entry.basis(), entry.validFrom())
        .userId(entry.userId())
        .userName(entry.userName())
        .groupId(entry.groupId())
        .groupName(entry.groupName())
        .assetRole(entry.assetRole())
        .spaceRole(entry.spaceRole())
        .validTo(entry.validTo());
  }
}
