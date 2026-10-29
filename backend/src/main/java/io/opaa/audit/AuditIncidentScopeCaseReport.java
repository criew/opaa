package io.opaa.audit;

import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.audit.AuditIncidentScopeGrantRepository.TransitionCount;
import io.opaa.compliance.InstanceDisclosure.CaseCount;
import io.opaa.compliance.InstanceDisclosure.IncidentScopeCases;
import io.opaa.compliance.InstanceDisclosure.PurposeCases;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Requests, approvals and ends of usability of the anlassbezogene Klärung per purpose in a period
 * of completed months, from the monthly counts that outlive the grants: no person, no grant,
 * nothing finer than a month, a count below {@link CaseCount#THRESHOLD} only as such. Ends are
 * counted ahead at approval; in a completed month they have all happened.
 */
@Service
public class AuditIncidentScopeCaseReport {

  private static final Map<AuditIncidentScopePurpose, String> LABELS =
      Map.of(
          AuditIncidentScopePurpose.SECURITY_INCIDENT, "Sicherheitsvorfall",
          AuditIncidentScopePurpose.UNAUTHORIZED_ACCESS_SUSPICION,
              "Verdacht auf unbefugten Zugriff",
          AuditIncidentScopePurpose.DATA_BREACH_INVESTIGATION,
              "Untersuchung einer Datenschutzverletzung",
          AuditIncidentScopePurpose.EXTERNAL_AUDIT_OR_INSPECTION, "Externe Prüfung oder Kontrolle");

  private static final List<String> NOTES =
      List.of(
          "Gezählt wird je Kalendermonat und Anlass, ohne Personen, Begründungen und Kennungen der"
              + " Freigaben; die Zahlen bleiben nach dem Löschen der Anträge und Freigaben erhalten.",
          "Beantragt: im Zeitraum gestellte Anträge. Freigegeben: im Zeitraum im Vier-Augen-Prinzip"
              + " erteilte Freigaben. Abgelaufen: Freigaben, deren Nutzbarkeit von "
              + AuditIncidentScopeGrant.USABLE_WINDOW.toDays()
              + " Tagen im Zeitraum endete.",
          "Ausgewiesen werden nur abgeschlossene Kalendermonate: Ein Berichtszeitraum, der in den"
              + " laufenden Monat oder später reicht, endet mit dem letzten abgeschlossenen Monat;"
              + " das ausgewiesene Ende steht unter „to“. So lässt auch ein wiederholter Abruf"
              + " keinen Antrag erkennen, solange sein Monat läuft.",
          "Eine Ablehnung gibt es nicht: Ein Antrag, der nicht freigegeben werden soll, bleibt"
              + " unbeantwortet und wird mit Ablauf seiner Frist gelöscht. Die Differenz zwischen"
              + " beantragt und freigegeben ist keine Zahl abgelehnter Anträge, weil Antrag und"
              + " Freigabe in verschiedenen Monaten liegen können.",
          "Angehaltene Chat-Löschungen: wie oft die Verwaltung im Zeitraum die Löschung der Chats"
              + " eines gesperrten Kontos angehalten hat. Eine Klärung, die die Löschung aufhält,"
              + " zählt dort nicht mit, sie steht in den Zahlen der Klärungen.",
          "Zahlen von 1 bis "
              + (CaseCount.THRESHOLD - 1)
              + " erscheinen als „weniger als "
              + CaseCount.THRESHOLD
              + "“, damit ein einzelner Fall nicht hervortritt. Überlappende Berichtszeiträume"
              + " lassen die Zahl eines Monats dennoch erschließen; jeder Abruf steht mit seinem"
              + " Zeitraum im Revisionsprotokoll.");

  private final AuditIncidentScopeGrantRepository repository;
  private final ChatCleanupHoldCounts holdCounts;
  private final Clock clock;

  AuditIncidentScopeCaseReport(
      AuditIncidentScopeGrantRepository repository, ChatCleanupHoldCounts holdCounts, Clock clock) {
    this.repository = repository;
    this.holdCounts = holdCounts;
    this.clock = clock;
  }

  /**
   * The counts of {@code organizationId} in {@code period}, every purpose of the closed list.
   *
   * @throws IllegalArgumentException when {@code period} reaches into the running month
   */
  public IncidentScopeCases report(UUID organizationId, ReportingPeriod period) {
    LocalDate runningMonth = LocalDate.now(clock).withDayOfMonth(1);
    if (!period.to().isBefore(runningMonth)) {
      throw new IllegalArgumentException(
          "a reporting period ends before the running month, got " + period.to());
    }
    List<TransitionCount> rows =
        repository.sumTransitions(organizationId, period.from(), period.to().withDayOfMonth(1));
    Map<String, Long> totals = new HashMap<>();
    for (TransitionCount row : rows) {
      totals.merge(row.getPurpose() + "/" + row.getTransition(), row.getTotal(), Long::sum);
    }
    List<PurposeCases> purposes = new ArrayList<>();
    for (AuditIncidentScopePurpose purpose : AuditIncidentScopePurpose.values()) {
      purposes.add(
          new PurposeCases(
              purpose.name(),
              LABELS.get(purpose),
              count(totals, purpose, AuditIncidentScopeTransition.REQUESTED),
              count(totals, purpose, AuditIncidentScopeTransition.APPROVED),
              count(totals, purpose, AuditIncidentScopeTransition.EXPIRED)));
    }
    CaseCount held =
        CaseCount.of(holdCounts.sum(organizationId, period.from(), period.to().withDayOfMonth(1)));
    return new IncidentScopeCases(period, purposes, held, NOTES);
  }

  private static CaseCount count(
      Map<String, Long> totals,
      AuditIncidentScopePurpose purpose,
      AuditIncidentScopeTransition transition) {
    return CaseCount.of(totals.getOrDefault(purpose.name() + "/" + transition.name(), 0L));
  }
}
