package io.opaa.selfdisclosure;

import io.opaa.compliance.ComplianceManifest;
import io.opaa.compliance.ComplianceManifest.DataEntry;
import io.opaa.compliance.ComplianceManifest.PersonalReference;
import io.opaa.compliance.ComplianceManifest.TableRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the overview of a self-disclosure says from the fact manifest: per category its purpose and
 * the columns or tables left out with their reason, and the data left out as a whole.
 */
record SelfDisclosureManifestFacts(
    Map<String, CategoryFacts> categories, List<Exclusion> whollyExcluded) {

  /** One category of the manifest. */
  record CategoryFacts(String title, String purpose, List<Exclusion> exclusions) {}

  /** Something left out of the disclosure and why, in German. */
  record Exclusion(String what, String reason) {}

  static SelfDisclosureManifestFacts of(ComplianceManifest manifest) {
    Map<String, CategoryFacts> categories = new LinkedHashMap<>();
    List<Exclusion> whollyExcluded = new ArrayList<>();
    for (DataEntry data : manifest.data()) {
      if (data.selfDisclosed()) {
        categories.put(
            data.id(), new CategoryFacts(data.title(), data.purpose(), exclusionsOf(data)));
      } else if (data.personalReference() != PersonalReference.NONE) {
        whollyExcluded.add(new Exclusion(data.title(), reasonOf(data)));
      }
    }
    return new SelfDisclosureManifestFacts(Map.copyOf(categories), List.copyOf(whollyExcluded));
  }

  boolean isCategory(String id) {
    return categories.containsKey(id);
  }

  private static List<Exclusion> exclusionsOf(DataEntry data) {
    List<Exclusion> exclusions = new ArrayList<>();
    for (TableRef table : data.tables()) {
      if (table.person() == null) {
        continue;
      }
      if (!table.person().reachesPerson()) {
        exclusions.add(new Exclusion(table.name(), table.person().excluded()));
        continue;
      }
      table
          .person()
          .excludedColumnsOrEmpty()
          .forEach(
              (column, reason) ->
                  exclusions.add(new Exclusion(table.name() + "." + column, reason)));
    }
    return List.copyOf(exclusions);
  }

  private static String reasonOf(DataEntry data) {
    if (data.personExcluded() != null && !data.personExcluded().isBlank()) {
      return data.personExcluded();
    }
    Set<String> reasons = new LinkedHashSet<>();
    for (TableRef table : data.tables()) {
      if (table.person() != null && table.person().excluded() != null) {
        reasons.add(table.person().excluded());
      }
    }
    return String.join(" ", reasons);
  }
}
