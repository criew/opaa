package io.opaa.succession.web;

import io.opaa.api.dto.SuccessionEntryResponse;
import io.opaa.api.dto.SuccessionListResponse;
import io.opaa.api.dto.SuccessionReviewResponse;
import io.opaa.permission.SuccessionFinding;
import io.opaa.succession.SuccessionEntry;
import io.opaa.succession.SuccessionPage;
import io.opaa.succession.SuccessionReview;

/**
 * Maps the lifecycle's domain records onto their generated counterparts (ADR-0006).
 *
 * <p>The marking at an object carries <b>state and addressee only</b> - no date, no previous owner,
 * no reason (Personalrat Z5); the list, which only the administration sees, carries the owner as a
 * hint. Both wordings of the addressee come from one place, so the object and the list never say it
 * differently.
 */
final class SuccessionResponseMapper {

  private SuccessionResponseMapper() {}

  static SuccessionListResponse toResponse(SuccessionPage page) {
    return new SuccessionListResponse(
        page.entries().stream().map(SuccessionResponseMapper::toResponse).toList(),
        page.page(),
        page.size(),
        page.totalElements(),
        page.totalPages());
  }

  static SuccessionEntryResponse toResponse(SuccessionEntry entry) {
    SuccessionFinding finding = entry.finding();
    return new SuccessionEntryResponse(
            finding.objectType(),
            finding.objectId(),
            finding.objectName(),
            finding.addressee(),
            finding.affectedObjects(),
            entry.highlighted())
        .caseId(entry.caseId())
        .assetType(
            finding.assetType() == null
                ? null
                : io.opaa.api.dto.AssetType.fromValue(finding.assetType().value()))
        .addresseeLabel(finding.addresseeLabel())
        .ownerHint(finding.ownerHint())
        .membershipHints(finding.membershipHints())
        .firstSeenAt(entry.firstSeenAt())
        .lastReviewedAt(entry.lastReviewedAt())
        .lastReviewReason(entry.lastReviewReason());
  }

  static SuccessionReviewResponse toResponse(SuccessionReview review) {
    return new SuccessionReviewResponse(
        review.getId(), review.getCaseId(), review.getReviewedAt(), review.getReason());
  }
}
