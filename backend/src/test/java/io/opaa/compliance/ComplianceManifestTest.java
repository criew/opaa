package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.SystemRole;
import io.opaa.architecture.MainClasses;
import io.opaa.compliance.ComplianceManifest.AccessPath;
import io.opaa.compliance.ComplianceManifest.AuditEventEntry;
import io.opaa.compliance.ComplianceManifest.DataFlowEntry;
import io.opaa.compliance.ComplianceManifest.RoleEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The completeness guard on the manifest delivered with the build (ADR-0052, Entscheidung 7): one
 * entry per value of the protocol, role and capability enums, per table and per column of the
 * Liquibase schema, a current checksum per prompt and one entry per {@link DataFlow} id
 * (Entscheidung 8) - and no entry without its counterpart. A failure names the gap, the area file
 * and the YAML to paste ({@link ManifestCompleteness}).
 */
class ComplianceManifestTest {

  private static final ComplianceManifest MANIFEST = ComplianceManifestLoader.load();

  @Test
  void isReadFromTheClasspathWithAnAreaPerFile() {
    assertThat(MANIFEST.areas()).isNotEmpty().doesNotContainValue("");
  }

  @Test
  void describesEveryAuditEventTypeAndNoOther() {
    List<String> gaps =
        ManifestCompleteness.enumGaps(
            AuditEventType.class,
            "auditEvents",
            areas(MANIFEST.auditEvents(), AuditEventEntry::id, AuditEventEntry::area),
            "trigger: <when the event is written, in German>");

    assertThat(gaps).withFailMessage(() -> ManifestCompleteness.report("enums", gaps)).isEmpty();
  }

  @Test
  void describesEverySystemRoleAndEveryCapabilityAndNoOther() {
    String mandatory = "seesAboutOthers: <what the holder sees about other persons, in German>";
    List<String> gaps =
        new ArrayList<>(
            ManifestCompleteness.enumGaps(
                SystemRole.class,
                "roles",
                areas(MANIFEST.roles(), RoleEntry::id, RoleEntry::area),
                mandatory));
    gaps.addAll(
        ManifestCompleteness.enumGaps(
            Capability.class,
            "capabilities",
            areas(MANIFEST.capabilities(), RoleEntry::id, RoleEntry::area),
            mandatory));

    assertThat(gaps).withFailMessage(() -> ManifestCompleteness.report("enums", gaps)).isEmpty();
  }

  /** A prompt change fails here until its checksum is updated in {@code prompts.yaml}. */
  @Test
  void everyPromptChecksumMatchesTheTextAtItsLocation() {
    assertThat(MANIFEST.prompts()).isNotEmpty();
    List<String> gaps = ManifestCompleteness.promptGaps(MANIFEST.prompts());

    assertThat(gaps)
        .withFailMessage(() -> ManifestCompleteness.report("prompt texts", gaps))
        .isEmpty();
  }

  @Test
  void describesEveryTableAndColumnOfTheSchemaAndNoOther() {
    List<String> gaps =
        ManifestCompleteness.schemaGaps(MANIFEST, ManifestCompleteness.deliveredSchema());

    assertThat(gaps)
        .withFailMessage(() -> ManifestCompleteness.report("Liquibase schema", gaps))
        .isEmpty();
  }

  @Test
  void describesTheAiFunctions() {
    assertThat(MANIFEST.aiFunctions()).isNotEmpty();
  }

  /**
   * A datum without a table lies outside the database (log, file system, object storage), so it
   * cannot be read through the database; its direct access path is {@code STORAGE}.
   */
  @Test
  void noDatumOutsideTheDatabaseIsReadThroughTheDatabase() {
    List<String> gaps =
        MANIFEST.data().stream()
            .filter(entry -> entry.tables().isEmpty())
            .filter(entry -> entry.accessPaths().contains(AccessPath.DATABASE))
            .map(
                entry ->
                    "data '"
                        + entry.id()
                        + "' in compliance/manifest/"
                        + entry.area()
                        + ".yaml has no table but accessPaths "
                        + entry.accessPaths()
                        + " - replace DATABASE with STORAGE")
            .toList();

    assertThat(gaps)
        .withFailMessage(() -> ManifestCompleteness.report("access paths", gaps))
        .isEmpty();
  }

  /**
   * Every id a main class names in {@link DataFlow} has a {@code dataFlows} entry, and every entry
   * is named by at least one class (ADR-0052, Entscheidung 8).
   */
  @Test
  void describesEveryDataFlowOfTheCodeAndNoOther() {
    Map<String, Set<String>> anchored = new TreeMap<>();
    MainClasses.get().stream()
        .filter(javaClass -> javaClass.isAnnotatedWith(DataFlow.class))
        .forEach(
            javaClass -> {
              for (String id : javaClass.getAnnotationOfType(DataFlow.class).value()) {
                anchored.computeIfAbsent(id, key -> new TreeSet<>()).add(javaClass.getName());
              }
            });
    Map<String, String> described =
        areas(MANIFEST.dataFlows(), DataFlowEntry::id, DataFlowEntry::area);

    List<String> gaps = new ArrayList<>();
    anchored.forEach(
        (id, classes) -> {
          if (!described.containsKey(id)) {
            gaps.add(
                "@DataFlow(\""
                    + id
                    + "\") on "
                    + classes
                    + " has no dataFlows entry - add to compliance/manifest/outbound.yaml:\n"
                    + "  - id: "
                    + id
                    + "\n    group: <MODEL | CONNECTOR | MAIL | IDENTITY | STORAGE>"
                    + "\n    viaOutboundProxy: <true if it takes the outbound proxy (OutboundProxySelector)>"
                    + "\n    target: <who receives the data, in German>"
                    + "\n    data: <what is sent>\n    trigger: <when it is sent>"
                    + "\n    switchOff: <whether and how it can be switched off>"
                    + "\n    settings: [<configuration keys that control it>]");
          }
        });
    for (DataFlowEntry entry : MANIFEST.dataFlows()) {
      if (!anchored.containsKey(entry.id())) {
        gaps.add(
            "dataFlows entry '"
                + entry.id()
                + "' in compliance/manifest/"
                + entry.area()
                + ".yaml is named by no @DataFlow - annotate the class that sends it, or remove"
                + " the entry");
      }
    }

    assertThat(gaps)
        .withFailMessage(() -> ManifestCompleteness.report("data flows", gaps))
        .isEmpty();
  }

  private static <T> Map<String, String> areas(
      List<T> entries, Function<T, String> id, Function<T, String> area) {
    Map<String, String> result = new LinkedHashMap<>();
    entries.forEach(entry -> result.put(id.apply(entry), area.apply(entry)));
    return result;
  }
}
