package io.opaa.permission.web;

import io.opaa.api.dto.CapabilityGrantResponse;
import io.opaa.api.dto.CapabilityOverviewResponse;
import io.opaa.api.dto.MyCapabilitiesResponse;
import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.permission.CapabilityGrant;
import io.opaa.permission.CapabilityGrantView;
import io.opaa.permission.CapabilityOverview;
import io.opaa.permission.CapabilityService;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Maps the capability domain records onto their generated response counterparts (ADR-0006) and
 * builds the German plain-text line of the administration overview - the one display line ADR-0036,
 * Entscheidung 5 asks for, so the delivered state is read rather than reconstructed from a list of
 * rows.
 */
public final class CapabilityResponseMapper {

  private CapabilityResponseMapper() {}

  public static MyCapabilitiesResponse toMyCapabilities(Set<Capability> capabilities) {
    return new MyCapabilitiesResponse(
        Arrays.stream(Capability.values()).filter(capabilities::contains).toList());
  }

  static List<CapabilityOverviewResponse> toOverviewResponses(List<CapabilityOverview> overviews) {
    return overviews.stream().map(CapabilityResponseMapper::toOverviewResponse).toList();
  }

  static CapabilityOverviewResponse toOverviewResponse(CapabilityOverview overview) {
    return new CapabilityOverviewResponse(
            overview.capability(),
            CapabilityService.label(overview.capability()),
            statement(overview),
            overview.grants().stream().map(CapabilityResponseMapper::toResponse).toList())
        .scope(overview.scope())
        .scopeLabel(overview.scopeLabel());
  }

  static CapabilityGrantResponse toResponse(CapabilityGrantView view) {
    CapabilityGrant grant = view.grant();
    return new CapabilityGrantResponse(
            grant.getId(), grant.getCapability(), grant.getSubjectType(), grant.getCreatedAt())
        .scope(grant.getScope())
        .subjectId(grant.getSubjectId())
        .subjectName(view.subjectName())
        .grantedByUserId(grant.getGrantedByUserId());
  }

  /**
   * "Alle Konten dürfen Konnektorbibliotheken anlegen." - or, once the delivered state has been
   * narrowed, who may instead. Names at most three subjects and counts the rest, so the line stays
   * a line. A subject whose name no longer resolves is named by its kind alone, never by its id: a
   * UUID in a sentence meant to be read tells a reader nothing and carries an identifier into a
   * place that has no use for one.
   */
  private static String statement(CapabilityOverview overview) {
    if (overview.scope() != null) {
      return scopeStatement(overview);
    }
    String what = CapabilityService.creatable(overview.capability());
    List<CapabilityGrantView> grants = overview.grants();
    if (grants.isEmpty()) {
      return "Nur die Systemverwaltung darf " + what + " anlegen.";
    }
    if (reachesAllAccounts(grants)) {
      return "Alle Konten dürfen " + what + " anlegen.";
    }
    return subjects(grants) + " sowie die Systemverwaltung dürfen " + what + " anlegen.";
  }

  /** "Zugang Nextcloud intern: frei für Alle Konten." - one line per scope. */
  private static String scopeStatement(CapabilityOverview overview) {
    List<CapabilityGrantView> grants = overview.grants();
    String prefix = overview.scopeLabel() + ": ";
    if (grants.isEmpty()) {
      return prefix + "aus, nur die Systemverwaltung.";
    }
    if (reachesAllAccounts(grants)) {
      return prefix + "frei für Alle Konten.";
    }
    return prefix + "frei für " + subjects(grants) + " sowie die Systemverwaltung.";
  }

  private static boolean reachesAllAccounts(List<CapabilityGrantView> grants) {
    return grants.stream()
        .anyMatch(view -> view.grant().getSubjectType() == CapabilitySubjectType.ALL_ACCOUNTS);
  }

  private static String subjects(List<CapabilityGrantView> grants) {
    List<String> named =
        grants.stream().map(CapabilityResponseMapper::subjectLabel).sorted().limit(3).toList();
    String subjects = String.join(", ", named);
    if (grants.size() > named.size()) {
      subjects += " und " + (grants.size() - named.size()) + " weitere";
    }
    return subjects;
  }

  private static String subjectLabel(CapabilityGrantView view) {
    boolean group = view.grant().getSubjectType() == CapabilitySubjectType.GROUP;
    if (view.subjectName() == null) {
      return group ? "Eine Gruppe ohne auflösbaren Namen" : "Ein Konto ohne auflösbaren Namen";
    }
    return (group ? "Gruppe " : "Konto ") + view.subjectName();
  }
}
