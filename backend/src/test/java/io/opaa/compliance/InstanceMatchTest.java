package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.compliance.InstanceMatch.Entry;
import io.opaa.compliance.InstanceMatch.Export;
import io.opaa.compliance.InstanceMatch.OwnChange;
import io.opaa.compliance.InstanceMatch.Relevance;
import io.opaa.compliance.StageRules.Stage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InstanceMatchTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String RETENTION = "audit_retention_settings.retention_months";
  private static final String TOP_K = "opaa.search.top-k";
  private static final String COMMIT = "1111111111111111111111111111111111111111";

  /**
   * A manifest delta from v1.2.0 to v1.3.0 as complianceDelta writes it, reduced to its changes.
   */
  private static JsonNode delta(Map<?, ?>... changes) {
    return JSON.valueToTree(
        Map.of(
            "from", Map.of("version", "v1.2.0", "commit", COMMIT),
            "to", Map.of("version", "v1.3.0", "commit", "2".repeat(40)),
            "changes", List.of(changes)));
  }

  private static Map<String, Object> change(
      String section, String id, String kind, String field, Object before, Object after) {
    Map<String, Object> change = new java.util.LinkedHashMap<>();
    change.put("group", "DEFAULTS");
    change.put("section", section);
    change.put("id", id);
    change.put("title", id);
    change.put("kind", kind);
    change.put("field", field);
    change.put("before", before);
    change.put("after", after);
    change.put(
        "assessment",
        Map.of("stage", "ZUSTIMMUNG", "classes", List.of("Z7"), "reason", "x", "undecided", false));
    return change;
  }

  private static Map<String, Object> setting(String key, String value, boolean deviates) {
    Map<String, Object> setting = new java.util.LinkedHashMap<>();
    setting.put("key", key);
    setting.put("area", "x");
    setting.put("source", "DATABASE");
    setting.put("defaultValue", "12");
    setting.put("effectiveValue", value);
    setting.put("deviates", deviates);
    setting.put("sameOnAllInstances", true);
    setting.put("effect", "x");
    return setting;
  }

  /**
   * A configuration export as the disclosure endpoint writes it, reduced to what the match reads.
   */
  private static JsonNode export(
      String version, List<Map<String, Object>> settings, List<Map<String, Object>> flows) {
    Map<String, Object> retentionDatum =
        Map.of(
            "id",
            "audit-log",
            "personalReference",
            "EMPLOYEE",
            "retention",
            Map.of("rule", "x", "settings", List.of(RETENTION)));
    return JSON.valueToTree(
        Map.of(
            "opaaVersion",
            version,
            "commit",
            COMMIT,
            "generatedAt",
            "2026-10-24T08:00:00Z",
            "manifest",
            Map.of("areas", Map.of(), "data", List.of(retentionDatum)),
            "instance",
            Map.of(
                "settings", settings,
                "dataFlows", flows,
                "connectorTypes", List.of(),
                "modelProviders", List.of(),
                "instances", List.of())));
  }

  private static Export info(String version) {
    return new Export("export.json", version, COMMIT, "2026-10-24T08:00:00Z");
  }

  private static Entry only(InstanceMatch match) {
    assertThat(match.entries()).hasSize(1);
    return match.entries().getFirst();
  }

  @Test
  void aChangedDefaultTheInstanceOverridesDoesNotConcernIt() {
    JsonNode export = export("1.2.0", List.of(setting(TOP_K, "8", true)), List.of());

    InstanceMatch match =
        InstanceMatch.of(
            delta(change("settings", TOP_K, "CHANGED", "defaultValue", "5", "10")),
            info("1.2.0"),
            export,
            null,
            null);

    Entry entry = only(match);
    assertThat(entry.relevance()).isEqualTo(Relevance.OVERRIDDEN);
    assertThat(entry.reason()).contains("`8`");
    assertThat(match.notes()).isEmpty();
  }

  @Test
  void aChangedDefaultTheInstanceKeepsConcernsIt() {
    JsonNode export = export("1.2.0", List.of(setting(TOP_K, "5", false)), List.of());

    Entry entry =
        only(
            InstanceMatch.of(
                delta(change("settings", TOP_K, "CHANGED", "defaultValue", "5", "10")),
                info("1.2.0"),
                export,
                null,
                null));

    assertThat(entry.relevance()).isEqualTo(Relevance.CONCERNED);
  }

  @Test
  void aNewConnectorTypeTheInstanceDoesNotUseDoesNotConcernIt() {
    JsonNode export =
        export(
            "1.2.0",
            List.of(),
            List.of(
                Map.of("id", "source-web", "group", "CONNECTOR", "target", "x", "active", true)));

    Entry entry =
        only(
            InstanceMatch.of(
                delta(
                    change(
                        "dataFlows",
                        "source-dropbox",
                        "ADDED",
                        null,
                        null,
                        Map.of("id", "source-dropbox", "group", "CONNECTOR", "target", "Dropbox"))),
                info("1.2.0"),
                export,
                null,
                null));

    assertThat(entry.relevance()).isEqualTo(Relevance.NOT_SWITCHED_ON);
  }

  @Test
  void aChangedDataFlowConcernsTheInstanceOnlyWhileItIsSwitchedOn() {
    JsonNode export =
        export(
            "1.2.0",
            List.of(),
            List.of(
                Map.of("id", "source-web", "group", "CONNECTOR", "target", "x", "active", true),
                Map.of("id", "mail", "group", "MAIL", "target", "x", "active", false)));

    InstanceMatch match =
        InstanceMatch.of(
            delta(
                change("dataFlows", "source-web", "CHANGED", "target", "a", "b"),
                change("dataFlows", "mail", "CHANGED", "target", "a", "b"),
                change(
                    "dataFlows",
                    "telemetry",
                    "ADDED",
                    null,
                    null,
                    Map.of("id", "telemetry", "group", "STORAGE"))),
            info("1.2.0"),
            export,
            null,
            null);

    assertThat(match.entries())
        .extracting(Entry::relevance)
        .containsExactly(Relevance.CONCERNED, Relevance.NOT_SWITCHED_ON, Relevance.CONCERNED);
  }

  @Test
  void aProductChangeOutsideSettingsAndDataFlowsConcernsEveryInstance() {
    Entry entry =
        only(
            InstanceMatch.of(
                delta(change("auditEvents", "LOGIN", "ADDED", null, null, Map.of("id", "LOGIN"))),
                info("1.2.0"),
                export("1.2.0", List.of(), List.of()),
                null,
                null));

    assertThat(entry.relevance()).isEqualTo(Relevance.CONCERNED);
  }

  @Test
  void aRetentionExtendedBetweenTwoExportsIsAnOwnChangeProposedForZustimmung() {
    JsonNode before = export("1.2.0", List.of(setting(RETENTION, "12", false)), List.of());
    JsonNode after = export("1.2.0", List.of(setting(RETENTION, "24", true)), List.of());

    InstanceMatch match = InstanceMatch.of(delta(), info("1.2.0"), after, info("1.2.0"), before);

    assertThat(match.ownChanges()).hasSize(1);
    OwnChange change = match.ownChanges().getFirst();
    assertThat(change.id()).isEqualTo(RETENTION);
    assertThat(change.before()).isEqualTo("12");
    assertThat(change.after()).isEqualTo("24");
    assertThat(change.assessment().stage()).isEqualTo(Stage.ZUSTIMMUNG);
    assertThat(change.assessment().classes()).containsExactly("Z4");
    assertThat(InstanceMatchRenderer.markdown(match))
        .contains("## Änderungen der Dienststelle")
        .contains(
            "| `"
                + RETENTION
                + "` | Wert der Einstellung geändert | `12` | `24` | Zustimmung (Z4) |");
  }

  @Test
  void aDataFlowSwitchedOnBetweenTwoExportsIsAnOwnChangeProposedForZustimmung() {
    JsonNode before =
        export(
            "1.2.0",
            List.of(),
            List.of(
                Map.of("id", "source-smb", "group", "CONNECTOR", "target", "x", "active", false)));
    JsonNode after =
        export(
            "1.2.0",
            List.of(),
            List.of(
                Map.of("id", "source-smb", "group", "CONNECTOR", "target", "x", "active", true)));

    InstanceMatch match = InstanceMatch.of(delta(), info("1.2.0"), after, info("1.2.0"), before);

    assertThat(match.ownChanges())
        .singleElement()
        .satisfies(
            change -> {
              assertThat(change.change()).isEqualTo("Datenfluss eingeschaltet");
              assertThat(change.assessment().classes()).containsExactly("Z5");
            });
  }

  @Test
  void anExportOfAnotherVersionThanTheStartOfTheDeltaIsNamed() {
    InstanceMatch match =
        InstanceMatch.of(
            delta(),
            new Export("export.json", "1.1.0", "3".repeat(40), "x"),
            export("1.1.0", List.of(), List.of()),
            null,
            null);

    assertThat(match.notes()).singleElement().asString().contains("Version 1.1.0");
  }

  @Test
  void aFileThatIsNoExportIsRefused() {
    assertThatThrownBy(
            () ->
                InstanceMatch.of(
                    delta(), info("1.2.0"), JSON.valueToTree(Map.of("x", 1)), null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no configuration export");
  }

  @Test
  void theGeneratorWritesBothFormsFromFilesAlone(@TempDir Path directory) throws Exception {
    Path delta = directory.resolve("manifest-delta.json");
    Files.writeString(
        delta,
        JSON.writeValueAsString(
            delta(change("settings", TOP_K, "CHANGED", "defaultValue", "5", "10"))),
        StandardCharsets.UTF_8);
    Path export = directory.resolve("export.json");
    Files.writeString(
        export,
        JSON.writeValueAsString(export("1.2.0", List.of(setting(TOP_K, "8", true)), List.of())),
        StandardCharsets.UTF_8);
    Path out = directory.resolve("out");

    InstanceMatchGenerator.main(
        new String[] {
          "--out", out.toString(), "--delta", delta.toString(), "--export", export.toString()
        });

    String markdown = Files.readString(out.resolve(InstanceMatchGenerator.MARKDOWN_FILE));
    assertThat(markdown)
        .contains("# Abgleich des Update-Deltas mit der Instanz")
        .contains("**betrifft sie nicht (überschrieben)**")
        .contains("Kein früherer Export angegeben.");
    JsonNode json = JSON.readTree(Files.readString(out.resolve(InstanceMatchGenerator.JSON_FILE)));
    assertThat(json.path("changes").get(0).path("relevance").asString()).isEqualTo("OVERRIDDEN");
    assertThat(json.path("export").path("version").asString()).isEqualTo("1.2.0");
  }

  @Test
  void theSameFilesGiveTheSameOutput() {
    JsonNode export = export("1.2.0", List.of(setting(TOP_K, "8", true)), List.of());
    JsonNode delta = delta(change("settings", TOP_K, "CHANGED", "defaultValue", "5", "10"));

    InstanceMatch first = InstanceMatch.of(delta, info("1.2.0"), export, null, null);
    InstanceMatch second = InstanceMatch.of(delta, info("1.2.0"), export, null, null);

    assertThat(InstanceMatchRenderer.markdown(first))
        .isEqualTo(InstanceMatchRenderer.markdown(second));
    assertThat(InstanceMatchRenderer.json(first)).isEqualTo(InstanceMatchRenderer.json(second));
  }
}
