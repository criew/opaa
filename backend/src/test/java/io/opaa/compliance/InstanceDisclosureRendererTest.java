package io.opaa.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ComplianceManifest.DataFlowGroup;
import io.opaa.compliance.ComplianceManifest.SettingSource;
import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceDisclosure.DataFlowState;
import io.opaa.compliance.InstanceDisclosure.ModelProvider;
import io.opaa.compliance.InstanceDisclosure.Protocol;
import io.opaa.compliance.InstanceDisclosure.ProtocolField;
import io.opaa.compliance.InstanceDisclosure.QueryPath;
import io.opaa.compliance.InstanceDisclosure.SettingValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The readable Markdown form of the disclosure: a head with version and commit, the deviations
 * first, then every setting, data flow, connector type, model provider, stored datum and the
 * description of the audit log with the query paths there are and are not.
 */
class InstanceDisclosureRendererTest {

  private static final ComplianceManifest MANIFEST = ComplianceManifestLoader.load();

  private static final InstanceDisclosure DISCLOSURE =
      new InstanceDisclosure(
          "1.4.0",
          "abc123",
          Instant.parse("2026-10-17T08:00:00Z"),
          MANIFEST,
          List.of(
              new SettingValue(
                  "audit-log",
                  "audit_retention_settings.retention_months",
                  SettingSource.DATABASE,
                  "36",
                  "24",
                  true,
                  Instant.parse("2026-10-01T10:00:00Z"),
                  "12 bis 120 Monate",
                  "Aufbewahrungsfrist des Protokolls"),
              new SettingValue(
                  "configuration",
                  "opaa.rerank.enabled",
                  SettingSource.PROPERTY,
                  "false",
                  null,
                  false,
                  null,
                  null,
                  "Ob a|b")),
          List.of(
              new DataFlowState("source-web", DataFlowGroup.CONNECTOR, "Webserver", true),
              new DataFlowState("model-endpoint", DataFlowGroup.MODEL, "Formular", null)),
          List.of(new ConnectorUsage("HTTP_DIRECTORY", 1, 0)),
          List.of(new ModelProvider("CHAT", "Hausmodell", "https://llm.example/v1", "llama", true)),
          new Protocol(
              List.of(
                  new ProtocolField(
                      "recorded_at", "Zeitpunkt auf die Mikrosekunde", "Zeitachse", "24 Monate")),
              List.of(new QueryPath("nach Objekt", "alle Ereignisse an einem Objekt")),
              List.of("kein Filter nach handelnder Person"),
              "Wird nicht erhoben"));

  @Test
  void headsTheDocumentWithVersionCommitAndTime() {
    String markdown = InstanceDisclosureRenderer.render(DISCLOSURE);

    assertThat(markdown)
        .startsWith("# Auskunft über die Datenerhebung\n")
        .contains("OPAA-Version: 1.4.0")
        .contains("Commit: abc123")
        .contains("2026-10-17T08:00:00Z")
        .contains(FactSheetRenderer.NOTICE);
  }

  @Test
  void listsTheDeviationsBeforeEverySetting() {
    String markdown = InstanceDisclosureRenderer.render(DISCLOSURE);

    int deviations = markdown.indexOf("## Abweichungen von der Voreinstellung");
    int settings = markdown.indexOf("## Einstellungen mit Datenwirkung");
    assertThat(deviations).isPositive().isLessThan(settings);
    String deviationSection = markdown.substring(deviations, settings);
    assertThat(deviationSection)
        .contains("| `audit_retention_settings.retention_months` | 36 | 24 | 2026-10-01T10:00:00Z |")
        .doesNotContain("opaa.rerank.enabled");
  }

  @Test
  void saysWhatItCouldNotDetermineAndEscapesTableCells() {
    String markdown = InstanceDisclosureRenderer.render(DISCLOSURE);

    assertThat(markdown).contains("nicht ermittelt").contains("Ob a\\|b");
    assertThat(markdown)
        .contains("| `source-web` | CONNECTOR | Webserver | ja |")
        .contains("| `model-endpoint` | MODEL | Formular | nur auf Handlung |");
  }

  @Test
  void describesConnectorsModelsDataAndTheAuditLog() {
    String markdown = InstanceDisclosureRenderer.render(DISCLOSURE);

    assertThat(markdown)
        .contains("## Konnektortypen in Gebrauch")
        .contains("| `HTTP_DIRECTORY` | 1 | 0 |")
        .contains("## Modellanbieter")
        .contains("| CHAT | Hausmodell | https://llm.example/v1 | llama | ja |")
        .contains("## Gespeicherte Daten")
        .contains("`audit-log`")
        .contains("## Protokoll")
        .contains("### Ereignisarten")
        .contains("`AUDIT_LOG_ACCESSED`")
        .contains("| `recorded_at` | Zeitpunkt auf die Mikrosekunde | Zeitachse | 24 Monate |")
        .contains("### Vorhandene Abfragewege")
        .contains("### Nicht vorhandene Abfragewege")
        .contains("- kein Filter nach handelnder Person")
        .contains("Wird nicht erhoben");
  }

  @Test
  void theManifestObjectIsTheOneOfTheFactSheetsJsonForm() {
    Map<String, Object> manifest = InstanceDisclosureRenderer.manifestObject(MANIFEST);

    assertThat(manifest).containsKeys("areas", "data", "auditEvents", "settings", "dataFlows");
    assertThat(manifest.toString()).doesNotContain("locatedOnce");
  }
}
