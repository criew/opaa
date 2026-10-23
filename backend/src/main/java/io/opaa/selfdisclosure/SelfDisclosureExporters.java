package io.opaa.selfdisclosure;

import io.opaa.compliance.ComplianceManifestLoader;
import io.opaa.compliance.SelfDisclosureExporter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Every {@link SelfDisclosureExporter} bean of the application, in the order of their category id,
 * with the facts of the manifest the overview needs. Refuses the start for two exporters of one
 * category and for a category the manifest does not lead as one. A category of the manifest without
 * exporter is named in the overview as not yet contained; that each is listed as pending, {@code
 * SelfDisclosureCoverageTest} checks.
 */
@Component
public class SelfDisclosureExporters {

  private final List<SelfDisclosureExporter> exporters;
  private final SelfDisclosureManifestFacts facts;

  @Autowired
  public SelfDisclosureExporters(List<SelfDisclosureExporter> exporters) {
    this(exporters, SelfDisclosureManifestFacts.of(ComplianceManifestLoader.load()));
  }

  SelfDisclosureExporters(
      List<SelfDisclosureExporter> exporters, SelfDisclosureManifestFacts facts) {
    Set<String> seen = new HashSet<>();
    for (SelfDisclosureExporter exporter : exporters) {
      String id = exporter.category().id();
      if (!seen.add(id)) {
        throw new IllegalStateException("Two self-disclosure exporters deliver category " + id);
      }
      if (!facts.isCategory(id)) {
        throw new IllegalStateException(
            "Self-disclosure exporter "
                + exporter.getClass().getName()
                + " delivers '"
                + id
                + "', which the fact manifest does not lead as a category with a path to the"
                + " person");
      }
    }
    this.exporters =
        exporters.stream()
            .sorted(Comparator.comparing(exporter -> exporter.category().id()))
            .toList();
    this.facts = facts;
  }

  List<SelfDisclosureExporter> all() {
    return exporters;
  }

  List<String> categoryIds() {
    return exporters.stream().map(exporter -> exporter.category().id()).toList();
  }

  SelfDisclosureManifestFacts facts() {
    return facts;
  }

  /** The titles of the manifest's categories no exporter delivers yet, sorted by id. */
  List<String> missingCategoryTitles() {
    Set<String> delivered = Set.copyOf(categoryIds());
    return facts.categories().entrySet().stream()
        .filter(entry -> !delivered.contains(entry.getKey()))
        .sorted(java.util.Map.Entry.comparingByKey())
        .map(entry -> entry.getValue().title())
        .toList();
  }
}
