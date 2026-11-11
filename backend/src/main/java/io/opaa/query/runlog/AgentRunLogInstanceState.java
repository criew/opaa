package io.opaa.query.runlog;

import io.opaa.compliance.InstanceDisclosure.DataSetDescription;
import io.opaa.compliance.InstanceDisclosure.ProtocolField;
import io.opaa.compliance.InstanceDisclosure.QueryPath;
import io.opaa.compliance.InstanceDisclosure.StoredValue;
import io.opaa.compliance.InstanceStateSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The retention periods of the agent run logs as this instance holds them, and both logs described
 * field by field for the staff council (ADR-0070, Entscheidung 4, Bestand b; ADR-0073, Entscheidung
 * 7). A new column of {@code agent_step_log} or {@code agent_sandbox_log}, or a new way to read
 * them, changes these lists in the same change.
 */
@Component
class AgentRunLogInstanceState implements InstanceStateSource {

  private final AgentRunLogRetentionSettingsRepository repository;

  AgentRunLogInstanceState(AgentRunLogRetentionSettingsRepository repository) {
    this.repository = repository;
  }

  @Override
  public Map<String, StoredValue> storedSettings() {
    return repository
        .findSingleton()
        .map(
            settings ->
                Map.of(
                    "agent_run_log_retention_settings.step_log_retention_days",
                    new StoredValue(
                        Integer.toString(settings.getStepLogRetentionDays()),
                        settings.getUpdatedAt()),
                    "agent_run_log_retention_settings.sandbox_log_retention_days",
                    new StoredValue(
                        Integer.toString(settings.getSandboxLogRetentionDays()),
                        settings.getUpdatedAt())))
        .orElse(Map.of());
  }

  @Override
  public List<DataSetDescription> dataSetDescriptions() {
    Optional<AgentRunLogRetentionSettings> settings = repository.findSingleton();
    String stepRetention =
        retention(settings.map(AgentRunLogRetentionSettings::getStepLogRetentionDays));
    String sandboxRetention =
        retention(settings.map(AgentRunLogRetentionSettings::getSandboxLogRetentionDays));
    return List.of(stepLog(stepRetention), sandboxLog(sandboxRetention));
  }

  /** Ids sort the step log before the sandbox log in the disclosure. */
  static final String STEP_LOG_ID = "agent-run-log";

  static final String SANDBOX_LOG_ID = "agent-run-log-sandbox";

  private static String retention(Optional<Integer> days) {
    return days.map(
            value ->
                "Frist des Protokolls, in dieser Installation "
                    + value
                    + " Tage; gelöscht werden ganze abgelaufene Tage, nicht abschaltbar")
        .orElse("Frist des Protokolls; gelöscht werden ganze abgelaufene Tage, nicht abschaltbar");
  }

  private static final List<QueryPath> QUERY_PATHS =
      List.of(
          new QueryPath(
              "ein Lauf",
              "Nur die Systemverwaltung der eigenen Organisation, mit genau einer Laufkennung und"
                  + " einem Anlass als Pflichtangabe; jeder Abruf, auch ein abgewiesener, steht im"
                  + " Revisionsprotokoll. Ein Lauf einer fremden Organisation antwortet wie ein"
                  + " unbekannter. Die Revision hat keinen Leseweg."));

  private static final List<String> ABSENT_QUERY_PATHS =
      List.of(
          "Keine Oberfläche, nur die eine Schnittstelle für einen Lauf",
          "Keine Liste der Läufe und keine Suche über Läufe",
          "Kein Filter, keine Gruppierung und keine Sortierung nach Person; das Protokoll hat"
              + " keinen Kontobezug. Über die Datenbank ist es mit der Laufkennung im Chat einer"
              + " Person verknüpfbar; die Anwendung bietet das nicht",
          "Kein Export und kein Vollabzug",
          "Keine Teilfrage, kein Suchtext, kein Skript und kein Dateiinhalt, auch nicht abgeleitet");

  private static DataSetDescription stepLog(String retention) {
    return new DataSetDescription(
        STEP_LOG_ID,
        "Schrittprotokoll der Agentenläufe",
        "Fehlersuche in einem Agentenlauf: welches Werkzeug ein Schritt mit welchem Ergebnis und"
            + " welcher Dauer lief. Was die Person an ihrer Antwort sieht, steht im Chat, nicht"
            + " hier.",
        List.of(
            new ProtocolField(
                "run_id",
                "Zufällige Kennung des Laufs, steht auch an der Antwort im Chat",
                "Den einen Lauf finden, nach dem gefragt wird",
                retention),
            new ProtocolField(
                "step_index", "Nummer des Schritts im Lauf", "Reihenfolge der Schritte", retention),
            new ProtocolField(
                "recorded_at",
                "Zeitpunkt nach Datenbankuhr",
                "Grundlage der Löschung nach Tagen",
                retention),
            new ProtocolField(
                "organization_id", "Kennung der Organisation", "Mandantengrenze", retention),
            new ProtocolField("tool", "Name des Werkzeugs", "Welches Werkzeug lief", retention),
            new ProtocolField(
                "status",
                "Ergebnis des Schritts, etwa erledigt, leer oder Argument ignoriert",
                "Wie der Schritt endete",
                retention),
            new ProtocolField(
                "hit_count",
                "Zahl der gefundenen Textstellen, leer bei Werkzeugen ohne Treffer",
                "Ob eine Suche etwas fand",
                retention),
            new ProtocolField(
                "duration_ms", "Dauer in Millisekunden", "Langsame Schritte finden", retention),
            new ProtocolField(
                "abort_reason",
                "Grund, wenn ein Schritt nicht lief oder scheiterte, etwa eine erreichte Grenze",
                "Ursache eines Abbruchs",
                retention)),
        QUERY_PATHS,
        ABSENT_QUERY_PATHS);
  }

  private static DataSetDescription sandboxLog(String retention) {
    String sandboxRetention = retention + "; nie länger als das Schrittprotokoll";
    return new DataSetDescription(
        SANDBOX_LOG_ID,
        "Sandboxprotokoll der Agentenläufe",
        "Fehlersuche, wenn ein Agent Code ausführen oder eine Vorlage füllen ließ: wie die"
            + " abgeschottete Umgebung des Laufs endete und wie viel sie erzeugte.",
        List.of(
            new ProtocolField(
                "run_id",
                "Zufällige Kennung des Laufs, steht auch an der Antwort im Chat",
                "Den einen Lauf finden, nach dem gefragt wird",
                sandboxRetention),
            new ProtocolField(
                "session_ref",
                "Technische Kennung der Sandbox beim Sandbox-Dienst",
                "Abgleich mit dem Log des Sandbox-Dienstes",
                sandboxRetention),
            new ProtocolField(
                "recorded_at",
                "Zeitpunkt nach Datenbankuhr",
                "Grundlage der Löschung nach Tagen",
                sandboxRetention),
            new ProtocolField(
                "organization_id", "Kennung der Organisation", "Mandantengrenze", sandboxRetention),
            new ProtocolField(
                "tool",
                "Name des Werkzeugs, das die Sandbox öffnete",
                "Welches Werkzeug sie brauchte",
                sandboxRetention),
            new ProtocolField(
                "status",
                "Abgeschlossen oder gescheitert",
                "Wie die Sandbox endete",
                sandboxRetention),
            new ProtocolField(
                "script_runs",
                "Zahl der ausgeführten Skripte",
                "Umfang des Laufs",
                sandboxRetention),
            new ProtocolField(
                "duration_ms", "Dauer in Millisekunden", "Langsame Läufe finden", sandboxRetention),
            new ProtocolField(
                "file_count",
                "Zahl der angehängten Dateien, ohne Namen",
                "Ob Dateien entstanden",
                sandboxRetention),
            new ProtocolField(
                "file_bytes",
                "Größe der angehängten Dateien zusammen",
                "Grenzen und Platzbedarf prüfen",
                sandboxRetention),
            new ProtocolField(
                "abort_reason",
                "Grund eines Abbruchs, etwa Start gescheitert oder Zeitgrenze",
                "Ursache eines Abbruchs",
                sandboxRetention)),
        QUERY_PATHS,
        ABSENT_QUERY_PATHS);
  }
}
