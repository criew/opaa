package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.SystemRole;
import io.opaa.architecture.MainClasses;
import io.opaa.compliance.ComplianceManifest.AuditEventEntry;
import io.opaa.compliance.ComplianceManifest.DataFlowEntry;
import io.opaa.compliance.ComplianceManifest.PromptEntry;
import io.opaa.compliance.ComplianceManifest.RoleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The manifest delivered with the build: readable from the classpath, valid against its schema, one
 * entry per value of the protocol, role and capability enums, and every prompt checksum current. A
 * prompt change fails here until its checksum is updated in {@code prompts.yaml}.
 */
class ComplianceManifestTest {

  private static final ComplianceManifest MANIFEST = ComplianceManifestLoader.load();

  @Test
  void isReadFromTheClasspathWithAnAreaPerFile() {
    assertThat(MANIFEST.areas()).isNotEmpty().doesNotContainValue("");
  }

  @Test
  void describesEveryAuditEventTypeAndNoOther() {
    assertThat(ids(MANIFEST.auditEvents(), AuditEventEntry::id))
        .containsExactlyInAnyOrderElementsOf(names(AuditEventType.values()));
  }

  @Test
  void describesEverySystemRoleAndEveryCapabilityAndNoOther() {
    assertThat(ids(MANIFEST.roles(), RoleEntry::id))
        .containsExactlyInAnyOrderElementsOf(names(SystemRole.values()));
    assertThat(ids(MANIFEST.capabilities(), RoleEntry::id))
        .containsExactlyInAnyOrderElementsOf(names(Capability.values()));
  }

  @Test
  void everyPromptChecksumMatchesTheTextAtItsLocation() {
    assertThat(MANIFEST.prompts()).isNotEmpty();
    for (PromptEntry prompt : MANIFEST.prompts()) {
      assertThat(PromptChecksum.of(prompt))
          .as(
              "prompt '%s' (%s) changed - review the wording, then set its sha256 in"
                  + " compliance/manifest/%s.yaml",
              prompt.id(),
              prompt.databaseDefault() != null ? prompt.databaseDefault() : prompt.classes(),
              prompt.area())
          .isEqualTo(prompt.sha256());
    }
  }

  @Test
  void describesTheAiFunctions() {
    assertThat(MANIFEST.aiFunctions()).isNotEmpty();
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
    Set<String> described = new TreeSet<>(ids(MANIFEST.dataFlows(), DataFlowEntry::id));

    List<String> gaps = new ArrayList<>();
    anchored.forEach(
        (id, classes) -> {
          if (!described.contains(id)) {
            gaps.add(
                "@DataFlow(\""
                    + id
                    + "\") on "
                    + classes
                    + " has no dataFlows entry - add to compliance/manifest/outbound.yaml:\n"
                    + "  - id: "
                    + id
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

    assertThat(gaps).withFailMessage(() -> String.join("\n", gaps)).isEmpty();
  }

  private static <T> List<String> ids(List<T> entries, Function<T, String> id) {
    return entries.stream().map(id).toList();
  }

  private static List<String> names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).toList();
  }
}
