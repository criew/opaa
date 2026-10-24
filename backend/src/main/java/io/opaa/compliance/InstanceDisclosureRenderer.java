package io.opaa.compliance;

import io.opaa.compliance.ComplianceManifest.AuditEventEntry;
import io.opaa.compliance.ComplianceManifest.DataEntry;
import io.opaa.compliance.InstanceDisclosure.CaseCount;
import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceDisclosure.DataFlowState;
import io.opaa.compliance.InstanceDisclosure.IncidentScopeCases;
import io.opaa.compliance.InstanceDisclosure.ModelProvider;
import io.opaa.compliance.InstanceDisclosure.Protocol;
import io.opaa.compliance.InstanceDisclosure.ProtocolField;
import io.opaa.compliance.InstanceDisclosure.PurposeCases;
import io.opaa.compliance.InstanceDisclosure.QueryPath;
import io.opaa.compliance.InstanceDisclosure.RunningInstance;
import io.opaa.compliance.InstanceDisclosure.SettingValue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Renders an {@link InstanceDisclosure} as Markdown for the data protection officer and the staff
 * council, and its manifest as the object the JSON form carries. The Markdown shows what the
 * disclosure holds and nothing more; every cell passes {@link FactSheetRenderer#text(String)}.
 */
public final class InstanceDisclosureRenderer {

  private static final String NOT_DETERMINED = "nicht ermittelt";

  private static final String DIFFERS_BETWEEN_INSTANCES = "abweichend zwischen Instanzen";

  /** The same record reading as the JSON form of the fact sheets, so both carry one object. */
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(MapperFeature.INFER_RECORD_GETTERS_FROM_COMPONENTS_ONLY).build();

  private InstanceDisclosureRenderer() {}

  /**
   * The manifest as a JSON object, field for field the {@code manifest} of {@code
   * faktenblaetter.json}, so the delta tooling reads both the same way.
   */
  public static Map<String, Object> manifestObject(ComplianceManifest manifest) {
    return JSON.convertValue(manifest, new TypeReference<Map<String, Object>>() {});
  }

  /** The disclosure as one Markdown document. */
  public static String render(InstanceDisclosure disclosure) {
    StringBuilder out = new StringBuilder();
    out.append("# Auskunft über die Datenerhebung\n\n");
    out.append("- OPAA-Version: ").append(shown(disclosure.opaaVersion())).append('\n');
    out.append("- Commit: ").append(shown(disclosure.commit())).append('\n');
    out.append("- Stand: ").append(disclosure.generatedAt()).append("\n\n");
    out.append(FactSheetRenderer.NOTICE).append("\n\n");
    out.append(
        "Die Auskunft verbindet das Fakten-Manifest dieser Version mit den Werten dieser"
            + " Installation. Sie enthält keine Geheimnisse, keine personenbezogenen Daten und keine"
            + " Inhalte; Adressen erscheinen ohne Zugangsdaten und Abfrageteil.\n\n");
    instances(out, disclosure.instances());
    deviations(out, disclosure.deviations());
    settings(out, disclosure.settings());
    dataFlows(out, disclosure.dataFlows());
    connectors(out, disclosure.connectorTypes());
    models(out, disclosure.modelProviders());
    data(out, disclosure.manifest().data());
    protocol(out, disclosure.manifest().auditEvents(), disclosure.protocol());
    return out.toString();
  }

  private static void instances(StringBuilder out, List<RunningInstance> instances) {
    out.append("## Laufende Instanzen\n\n");
    if (instances.isEmpty()) {
      out.append("Das Instanzregister nennt keine laufende Instanz.\n\n");
      return;
    }
    out.append(
        "Die Werte gelten für die ganze Installation. Eine Einstellung, deren Wert sich zwischen"
            + " laufenden Instanzen unterscheidet, erscheint ohne Wert und ist als abweichend"
            + " markiert.\n\n");
    out.append("| Version | Gestartet am |\n");
    out.append("|---|---|\n");
    for (RunningInstance instance : instances) {
      row(out, shown(instance.version()), time(instance.startedAt()));
    }
    out.append('\n');
  }

  private static void deviations(StringBuilder out, List<SettingValue> deviations) {
    out.append("## Abweichungen von der Voreinstellung\n\n");
    if (deviations.isEmpty()) {
      out.append("Keine Einstellung weicht von der Voreinstellung ab.\n\n");
      return;
    }
    out.append("| Schlüssel | Voreinstellung | Wert der Installation | Geändert am |\n");
    out.append("|---|---|---|---|\n");
    for (SettingValue value : deviations) {
      row(
          out,
          FactSheetRenderer.code(value.key()),
          shown(value.defaultValue()),
          shown(value.effectiveValue()),
          time(value.changedAt()));
    }
    out.append('\n');
  }

  private static void settings(StringBuilder out, List<SettingValue> settings) {
    out.append("## Einstellungen mit Datenwirkung\n\n");
    out.append(
        "| Schlüssel | Quelle | Voreinstellung | Wert der Installation | Abweichend | Geändert am"
            + " | Wirkung |\n");
    out.append("|---|---|---|---|---|---|---|\n");
    for (SettingValue value : settings) {
      row(
          out,
          FactSheetRenderer.code(value.key()),
          value.source().name(),
          shown(value.defaultValue()),
          value.sameOnAllInstances()
              ? value.effectiveValue() == null ? NOT_DETERMINED : shown(value.effectiveValue())
              : DIFFERS_BETWEEN_INSTANCES,
          value.deviates() ? "ja" : "nein",
          time(value.changedAt()),
          FactSheetRenderer.text(value.effect()));
    }
    out.append('\n');
  }

  private static void dataFlows(StringBuilder out, List<DataFlowState> flows) {
    out.append("## Ausgehende Datenflüsse\n\n");
    out.append("| Kennung | Gruppe | Ziel | Eingeschaltet |\n");
    out.append("|---|---|---|---|\n");
    for (DataFlowState flow : flows) {
      row(
          out,
          FactSheetRenderer.code(flow.id()),
          flow.group().name(),
          FactSheetRenderer.text(flow.target()),
          flow.active() == null ? "nur auf Handlung" : flow.active() ? "ja" : "nein");
    }
    out.append('\n');
  }

  private static void connectors(StringBuilder out, List<ConnectorUsage> connectors) {
    out.append("## Konnektortypen in Gebrauch\n\n");
    if (connectors.isEmpty()) {
      out.append("Keine Bibliothek und kein Zugang nutzt einen Konnektor.\n\n");
      return;
    }
    out.append("| Typ | Bibliotheken | Zugänge |\n");
    out.append("|---|---|---|\n");
    for (ConnectorUsage usage : connectors) {
      row(
          out,
          FactSheetRenderer.code(usage.type()),
          Long.toString(usage.libraries()),
          Long.toString(usage.connectionProfiles()));
    }
    out.append('\n');
  }

  private static void models(StringBuilder out, List<ModelProvider> providers) {
    out.append("## Modellanbieter\n\n");
    out.append("| Rolle | Name | Adresse | Modell | Aktiv |\n");
    out.append("|---|---|---|---|---|\n");
    for (ModelProvider provider : providers) {
      row(
          out,
          provider.role(),
          shown(provider.name()),
          shown(provider.baseUrl()),
          shown(provider.model()),
          provider.active() ? "ja" : "nein");
    }
    out.append('\n');
  }

  private static void data(StringBuilder out, List<DataEntry> data) {
    out.append("## Gespeicherte Daten\n\n");
    out.append("| Kennung | Datum | Zweck | Personenbezug | Frist | Sichtbarkeit |\n");
    out.append("|---|---|---|---|---|---|\n");
    for (DataEntry entry : data) {
      row(
          out,
          FactSheetRenderer.code(entry.id()),
          FactSheetRenderer.text(entry.title()),
          FactSheetRenderer.text(entry.purpose()),
          entry.personalReference().name(),
          FactSheetRenderer.text(entry.retention().rule()),
          FactSheetRenderer.text(String.join("; ", entry.visibility())));
    }
    out.append('\n');
  }

  private static void protocol(StringBuilder out, List<AuditEventEntry> events, Protocol protocol) {
    out.append("## Protokoll\n\n");
    out.append(
        "Die Beschreibung des Revisionsprotokolls, nicht sein Inhalt: keine Protokollsätze, keine"
            + " Namen, keine Pseudonymkennungen.\n\n");
    out.append("### Ereignisarten\n\n");
    out.append("| Ereignisart | Auslöser | Zweck |\n");
    out.append("|---|---|---|\n");
    for (AuditEventEntry event : events) {
      row(
          out,
          FactSheetRenderer.code(event.id()),
          FactSheetRenderer.text(event.trigger()),
          FactSheetRenderer.text(event.purpose()));
    }
    out.append("\n### Felder des Protokollsatzes\n\n");
    out.append("| Feld | Granularität | Zweck | Frist |\n");
    out.append("|---|---|---|---|\n");
    for (ProtocolField field : protocol.fields()) {
      row(
          out,
          FactSheetRenderer.code(field.name()),
          FactSheetRenderer.text(field.granularity()),
          FactSheetRenderer.text(field.purpose()),
          FactSheetRenderer.text(field.retention()));
    }
    out.append("\n### Vorhandene Abfragewege\n\n");
    out.append("| Abfrageweg | Beschreibung |\n");
    out.append("|---|---|\n");
    for (QueryPath path : protocol.queryPaths()) {
      row(out, FactSheetRenderer.text(path.name()), FactSheetRenderer.text(path.description()));
    }
    out.append("\n### Nicht vorhandene Abfragewege\n\n");
    for (String absent : protocol.absentQueryPaths()) {
      out.append("- ").append(FactSheetRenderer.text(absent)).append('\n');
    }
    out.append("\n### Netzadresse\n\n");
    out.append(FactSheetRenderer.text(protocol.networkAddress())).append('\n');
    incidentScopeCases(out, protocol.incidentScopeCases());
  }

  private static void incidentScopeCases(StringBuilder out, IncidentScopeCases cases) {
    out.append("\n### Anlassbezogene Klärungen im Berichtszeitraum\n\n");
    out.append("Berichtszeitraum: ")
        .append(cases.period().from())
        .append(" bis ")
        .append(cases.period().to())
        .append("\n\n");
    out.append("| Anlass | Beantragt | Freigegeben | Abgelaufen |\n");
    out.append("|---|---|---|---|\n");
    for (PurposeCases purpose : cases.purposes()) {
      row(
          out,
          FactSheetRenderer.text(purpose.label()),
          count(purpose.requested()),
          count(purpose.approved()),
          count(purpose.expired()));
    }
    out.append('\n');
    for (String note : cases.notes()) {
      out.append("- ").append(FactSheetRenderer.text(note)).append('\n');
    }
  }

  private static String count(CaseCount count) {
    return count.belowThreshold()
        ? "weniger als " + CaseCount.THRESHOLD
        : Long.toString(count.count());
  }

  private static void row(StringBuilder out, String... cells) {
    out.append("| ").append(String.join(" | ", cells)).append(" |\n");
  }

  /**
   * A value of the instance as a code span, so that nothing an administration typed (a model name,
   * an address) is rendered as a link, an image or markup.
   */
  private static String shown(String value) {
    if (value == null) {
      return "–";
    }
    String line = value.replaceAll("\\s*\\R\\s*", " ").strip();
    return line.isEmpty() ? "(leer)" : FactSheetRenderer.code(line);
  }

  private static String time(Instant value) {
    return value == null ? "–" : value.toString();
  }
}
