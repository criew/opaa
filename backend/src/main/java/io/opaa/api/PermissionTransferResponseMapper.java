package io.opaa.api;

import io.opaa.api.dto.PermissionTransferCountsResponse;
import io.opaa.api.dto.PermissionTransferMarkResponse;
import io.opaa.api.dto.PermissionTransferPreviewResponse;
import io.opaa.api.dto.PermissionTransferResponse;
import io.opaa.api.types.PermissionTransferScope;
import io.opaa.permission.PermissionTransfer;
import io.opaa.permission.PermissionTransferCounts;
import io.opaa.permission.PermissionTransferMark;
import io.opaa.permission.PermissionTransferPreview;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the transfer domain records onto their generated counterparts (ADR-0006) and writes the
 * German sentence the confirmation dialog shows ("12 Berechtigungen an 7 Bibliotheken, Mitglied in
 * 2 Spaces, …") - the one display line ADR-0036, Entscheidung 10 asks for.
 */
final class PermissionTransferResponseMapper {

  private PermissionTransferResponseMapper() {}

  static PermissionTransferPreviewResponse toResponse(PermissionTransferPreview preview) {
    PermissionTransferCountsResponse counts =
        toCounts(preview.counts(), preview.grantedAssets(), preview.spaces());
    return new PermissionTransferPreviewResponse(
            preview.previewId(),
            preview.source().type(),
            preview.source().id(),
            preview.target().type(),
            preview.target().id(),
            List.copyOf(preview.scope()),
            counts,
            summary(counts))
        .sourceName(preview.sourceLabel())
        .targetName(preview.targetLabel());
  }

  /**
   * The executed transfer. The distinct-object figures are not stored - the operation is over, and
   * what it moved is its row counts - so they repeat the row counts here rather than claiming a
   * precision the record does not hold.
   */
  static PermissionTransferResponse toResponse(PermissionTransfer transfer) {
    PermissionTransferCounts counts = transfer.counts();
    PermissionTransferCountsResponse body =
        toCounts(counts, counts.assetGrants(), counts.spaceMemberships());
    return new PermissionTransferResponse(
            transfer.getId(),
            transfer.getPerformedAt(),
            transfer.source().type(),
            transfer.source().id(),
            transfer.target().type(),
            transfer.target().id(),
            List.copyOf(transfer.getScope()),
            body,
            summary(body))
        .sourceName(transfer.getSourceLabel())
        .targetName(transfer.getTargetLabel());
  }

  /**
   * The note an object carries. A protected source group is named the way every other list names it
   * - by its protection, not by itself (ADR-0036, Entscheidung 9); the service has already decided
   * whether this caller may read the name at all.
   */
  static PermissionTransferMarkResponse toResponse(PermissionTransferMark mark) {
    if (mark == null) {
      return null;
    }
    return new PermissionTransferMarkResponse(mark.transferId(), mark.transferredAt())
        .sourceName(mark.sourceProtected() ? "Geschützte Gruppe" : mark.sourceLabel())
        .sourceProtected(mark.sourceProtected());
  }

  static List<PermissionTransferScope> toScope(List<PermissionTransferScope> scope) {
    return scope == null ? List.of() : scope;
  }

  private static PermissionTransferCountsResponse toCounts(
      PermissionTransferCounts counts, int grantedAssets, int spaces) {
    return new PermissionTransferCountsResponse(
        counts.assetGrants(),
        grantedAssets,
        counts.spaceMemberships(),
        spaces,
        counts.capabilities(),
        counts.ownedAssets(),
        counts.stewardships());
  }

  /** Names every part that is not empty, and says plainly when nothing is left to move. */
  private static String summary(PermissionTransferCountsResponse counts) {
    List<String> parts = new ArrayList<>();
    if (counts.getAssetGrants() > 0) {
      parts.add(
          count(counts.getAssetGrants(), "Berechtigung", "Berechtigungen")
              + " an "
              + count(counts.getGrantedAssets(), "Objekt", "Objekten"));
    }
    if (counts.getSpaceMemberships() > 0) {
      parts.add("Mitglied in " + count(counts.getSpaces(), "Space", "Spaces"));
    }
    if (counts.getCapabilities() > 0) {
      parts.add(count(counts.getCapabilities(), "Anlegerecht", "Anlegerechte"));
    }
    if (counts.getOwnedAssets() > 0) {
      parts.add("Eigentum an " + count(counts.getOwnedAssets(), "Objekt", "Objekten"));
    }
    if (counts.getStewardships() > 0) {
      parts.add("Verantwortung für " + count(counts.getStewardships(), "Gruppe", "Gruppen"));
    }
    if (parts.isEmpty()) {
      return "Die Quelle hält im gewählten Umfang nichts, was übertragen werden könnte.";
    }
    return String.join(", ", parts);
  }

  private static String count(int value, String singular, String plural) {
    return value + " " + (value == 1 ? singular : plural);
  }
}
