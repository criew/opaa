package io.opaa.api;

import io.opaa.api.dto.CapabilityGrantResponse;
import io.opaa.api.dto.CapabilityOverviewResponse;
import io.opaa.api.dto.MyCapabilitiesResponse;
import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.permission.CapabilityGrant;
import io.opaa.permission.CapabilityGrantView;
import io.opaa.permission.CapabilityOverview;
import io.opaa.permission.CapabilityService;
import java.util.List;
import java.util.Set;

/**
 * Maps the capability domain records onto their generated response counterparts (ADR-0006) and
 * builds the German plain-text line of the administration overview - the one display line ADR-0036,
 * Entscheidung 5 asks for, so the delivered state is read rather than reconstructed from a list of
 * rows.
 */
final class CapabilityResponseMapper {

  private CapabilityResponseMapper() {}

  static MyCapabilitiesResponse toMyCapabilities(Set<Capability> capabilities) {
    return new MyCapabilitiesResponse(
        java.util.Arrays.stream(Capability.values()).filter(capabilities::contains).toList());
  }

  static List<CapabilityOverviewResponse> toOverviewResponses(List<CapabilityOverview> overviews) {
    return overviews.stream().map(CapabilityResponseMapper::toOverviewResponse).toList();
  }

  static CapabilityOverviewResponse toOverviewResponse(CapabilityOverview overview) {
    return new CapabilityOverviewResponse(
        overview.capability(),
        CapabilityService.label(overview.capability()),
        statement(overview),
        overview.grants().stream().map(CapabilityResponseMapper::toResponse).toList());
  }

  static CapabilityGrantResponse toResponse(CapabilityGrantView view) {
    CapabilityGrant grant = view.grant();
    return new CapabilityGrantResponse(
            grant.getId(), grant.getCapability(), grant.getSubjectType(), grant.getCreatedAt())
        .subjectId(grant.getSubjectId())
        .subjectName(view.subjectName())
        .grantedByUserId(grant.getGrantedByUserId());
  }

  /**
   * "Alle Konten dürfen Konnektorbibliotheken anlegen." - or, once the delivery state has been
   * narrowed, who may instead. Names at most three subjects and counts the rest, so the line stays
   * a line.
   */
  private static String statement(CapabilityOverview overview) {
    String what = CapabilityService.creatable(overview.capability());
    List<CapabilityGrantView> grants = overview.grants();
    if (grants.isEmpty()) {
      return "Nur die Systemverwaltung darf " + what + " anlegen.";
    }
    if (grants.stream()
        .anyMatch(view -> view.grant().getSubjectType() == CapabilitySubjectType.ALL_ACCOUNTS)) {
      return "Alle Konten dürfen " + what + " anlegen.";
    }
    List<String> named = grants.stream().map(view -> subjectLabel(view)).sorted().limit(3).toList();
    String subjects = String.join(", ", named);
    if (grants.size() > named.size()) {
      subjects += " und " + (grants.size() - named.size()) + " weitere";
    }
    return subjects + " sowie die Systemverwaltung dürfen " + what + " anlegen.";
  }

  private static String subjectLabel(CapabilityGrantView view) {
    String kind =
        view.grant().getSubjectType() == CapabilitySubjectType.GROUP ? "Gruppe " : "Konto ";
    String name =
        view.subjectName() != null ? view.subjectName() : view.grant().getSubjectId().toString();
    return kind + name;
  }
}
