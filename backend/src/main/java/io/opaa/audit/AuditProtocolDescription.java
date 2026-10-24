package io.opaa.audit;

import io.opaa.compliance.InstanceDisclosure.IncidentScopeCases;
import io.opaa.compliance.InstanceDisclosure.Protocol;
import io.opaa.compliance.InstanceDisclosure.ProtocolField;
import io.opaa.compliance.InstanceDisclosure.QueryPath;
import java.util.List;

/**
 * The description of the audit log for the staff council ("Auszug für die Personalvertretung",
 * docs/features/security-and-compliance.md): every column of {@code audit_log} with granularity,
 * purpose and retention, the query paths {@link AuditQueryService} offers with the bounds of {@link
 * AuditAccessGate}, the self-disclosure of the person herself ({@link
 * AuditLogSelfDisclosureExporter}), and the paths that deliberately do not exist. A new column of
 * {@code audit_log} or a new query path changes this list in the same change.
 */
final class AuditProtocolDescription {

  private AuditProtocolDescription() {}

  /** The description with the retention period currently configured and the cases counted. */
  static Protocol of(int retentionMonths, IncidentScopeCases incidentScopeCases) {
    String retention =
        "Frist des Protokolls, in dieser Installation "
            + retentionMonths
            + " Monate; gelöscht werden ganze abgelaufene Monate";
    return new Protocol(
        fields(retention), queryPaths(), ABSENT_QUERY_PATHS, NETWORK_ADDRESS, incidentScopeCases);
  }

  private static final String NETWORK_ADDRESS =
      "Wird nicht erhoben: Der Protokollsatz hat kein Feld für die Netzadresse, und kein Schalter"
          + " nimmt sie auf.";

  private static final List<String> ABSENT_QUERY_PATHS =
      List.of(
          "Kein Filter nach handelnder Person, weder als Parameter noch als Spaltenfilter noch über"
              + " eine Sortierung, außer in der freigegebenen anlassbezogenen Klärung und in der"
              + " Selbstauskunft über die eigene Person",
          "Keine Gruppierung und keine Zählung je Person",
          "Keine Zeitreihe je Person, in keiner Auflösung",
          "Keine Freitextsuche über das Protokoll",
          "Kein Vollabzug ohne Objekt-, Zeitraum- oder Ereignisbezug, auch nicht als Export;"
              + " einzige Ausnahme die Selbstauskunft, nur über die eigene Person",
          "Keine Sicht „alle Ereignisse, bei denen Person X betroffen war“; für Dritte ist die"
              + " Ausnahme allein die freigegebene anlassbezogene Klärung im Vier-Augen-Prinzip, für"
              + " die Person selbst ihre Selbstauskunft");

  private static List<QueryPath> queryPaths() {
    String bounds =
        "; Zeitraum höchstens "
            + AuditAccessGate.MAX_TIME_RANGE_DAYS
            + " Tage, höchstens "
            + (AuditAccessGate.MAX_PAGE_INDEX + 1)
            + " Seiten zu je höchstens "
            + AuditAccessGate.MAX_PAGE_SIZE
            + " Einträgen, Anlass Pflicht; jeder Abruf wird selbst protokolliert";
    return List.of(
        new QueryPath(
            "nach Objekt",
            "Alle Ereignisse an einem Objekt, nie an einem Konto als Objekt" + bounds),
        new QueryPath("nach Zeitraum", "Alle Ereignisse eines Zeitraums" + bounds),
        new QueryPath("nach Ereignisart", "Alle Ereignisse einer Art in einem Zeitraum" + bounds),
        new QueryPath(
            "nach Vorgang", "Alle Einträge eines technischen Vorgangs, etwa eines Laufs" + bounds),
        new QueryPath(
            "anlassbezogene Klärung",
            "Die einzige Abfrage nach handelnder Person durch Dritte: alle Ereignisse der vorab"
                + " festgelegten Person und die Einträge, die sie als Besitzerin nennen, in einem"
                + " Zeitraum innerhalb des freigegebenen Zeitraums; nur mit"
                + " Freigabe im Vier-Augen-Prinzip durch eine zweite Person, nutzbar "
                + AuditIncidentScopeGrant.USABLE_WINDOW.toDays()
                + " Tage ab Freigabe"
                + bounds),
        new QueryPath(
            "Selbstauskunft",
            "Nur über die angemeldete Person selbst, nie über eine andere und ohne Parameter:"
                + " alle Ereignisse, in denen sie handelt, betroffen, Konto als Objekt oder als"
                + " Besitzerin genannt ist,"
                + " ohne Zeit- und Seitengrenze; ohne Pseudonyme und Begründungen, andere"
                + " Beteiligte als „andere Person“ oder „System“. Statt eines eigenen Abrufeintrags"
                + " stehen Beginn und Ende des Exports im Protokoll"));
  }

  private static List<ProtocolField> fields(String retention) {
    return List.of(
        new ProtocolField(
            "event_id", "Zufällige Kennung des Eintrags", "Eindeutigkeit des Eintrags", retention),
        new ProtocolField(
            "recorded_at",
            "Zeitpunkt auf die Mikrosekunde",
            "Zeitachse der Prüfung, Grundlage der Löschung nach Monaten",
            retention),
        new ProtocolField(
            "organization_id", "Kennung der Organisation", "Mandantengrenze", retention),
        new ProtocolField(
            "actor_kind",
            "Person, Dienstkonto oder Systemvorgang",
            "Wer gehandelt hat, der Art nach",
            retention),
        new ProtocolField(
            "actor_ref",
            "Pseudonym der handelnden Person; Zuordnung nur über eine getrennte Tabelle, die mit"
                + " dem Konto entfällt",
            "Zurechnung einer Handlung; Ausgabefeld, nie Filter",
            retention),
        new ProtocolField(
            "event_type", "Eine Art aus der geschlossenen Liste", "Was geschah", retention),
        new ProtocolField("object_type", "Art des Objekts", "Woran es geschah", retention),
        new ProtocolField(
            "object_id",
            "Kennung des Objekts; bei einem Konto als Objekt dessen Pseudonym",
            "Woran es geschah",
            retention),
        new ProtocolField(
            "object_label",
            "Name des Objekts zum Zeitpunkt des Ereignisses",
            "Lesbarkeit des Eintrags",
            retention),
        new ProtocolField(
            "subject_kind", "Person oder Gruppe", "Wen eine Rechteänderung betraf", retention),
        new ProtocolField(
            "subject_ref",
            "Pseudonym der betroffenen Person oder Kennung der Gruppe",
            "Wen eine Rechteänderung betraf",
            retention),
        new ProtocolField(
            "before",
            "Die geänderten Werte vorher, je Ereignisart festgelegt",
            "Nachweis der Änderung",
            retention),
        new ProtocolField(
            "after",
            "Die geänderten Werte nachher, je Ereignisart festgelegt",
            "Nachweis der Änderung",
            retention),
        new ProtocolField(
            "outcome", "Erfolg, abgewiesen oder fehlgeschlagen", "Ausgang der Handlung", retention),
        new ProtocolField(
            "reason",
            "Freitext bis 1000 Zeichen, etwa der Anlass einer Protokollabfrage",
            "Begründung, wo die Handlung eine verlangt",
            retention),
        new ProtocolField(
            "correlation_ref",
            "Kennung eines technischen Vorgangs",
            "Zusammenhang der Einträge eines Vorgangs",
            retention));
  }
}
