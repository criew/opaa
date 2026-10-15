package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.Capability;
import io.opaa.api.types.SystemRole;
import io.opaa.compliance.ComplianceManifest.AuditEventEntry;
import io.opaa.compliance.ComplianceManifest.RoleEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The completeness guard on the manifest delivered with the build (ADR-0052, Entscheidung 7): one
 * entry per value of the protocol, role and capability enums, per table and per column of the
 * Liquibase schema, and a current checksum per prompt - and no entry without its counterpart. A
 * failure names the gap, the area file and the YAML to paste ({@link ManifestCompleteness}).
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

  private static <T> Map<String, String> areas(
      List<T> entries, Function<T, String> id, Function<T, String> area) {
    Map<String, String> result = new LinkedHashMap<>();
    entries.forEach(entry -> result.put(id.apply(entry), area.apply(entry)));
    return result;
  }
}
