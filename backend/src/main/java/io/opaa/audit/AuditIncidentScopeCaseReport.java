package io.opaa.audit;

import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.audit.AuditIncidentScopeGrantRepository.TransitionCount;
import io.opaa.compliance.InstanceDisclosure.CaseCount;
import io.opaa.compliance.InstanceDisclosure.IncidentScopeCases;
import io.opaa.compliance.InstanceDisclosure.PurposeCases;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * How often the anlassbezogene Klärung was requested, approved and ended in a reporting period, by
 * purpose, for the disclosure of the data collection ("Auszug für die Personalvertretung"). It
 * reads the counts per month of {@code audit_incident_scope_monthly_counts}, which outlive the
 * grants, and gives no person, no reason, no grant and nothing finer than a month; a count below
 * {@link CaseCount#THRESHOLD} leaves only as such.
 *
 * <p>An end of usability is counted ahead at approval. For the months before the running one the
 * counts are therefore exact; for the running month the ends up to now are read from the grants,
 * which are deleted at the earliest at the end of the following year; later months show none.
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
              + " Tagen im Zeitraum endete, bis zum Zeitpunkt der Auskunft.",
          "Eine Ablehnung gibt es nicht: Ein Antrag, der nicht freigegeben werden soll, bleibt"
              + " unbeantwortet und wird mit Ablauf seiner Frist gelöscht. Die Differenz zwischen"
              + " beantragt und freigegeben ist keine Zahl abgelehnter Anträge, weil Antrag und"
              + " Freigabe in verschiedenen Monaten liegen können.",
          "Zahlen von 1 bis "
              + (CaseCount.THRESHOLD - 1)
              + " erscheinen als „weniger als "
              + CaseCount.THRESHOLD
              + "“, damit ein einzelner Fall nicht hervortritt. Überlappende Berichtszeiträume"
              + " lassen die Zahl eines Monats dennoch erschließen; jeder Abruf steht mit seinem"
              + " Zeitraum im Revisionsprotokoll.");

  private final AuditIncidentScopeGrantRepository repository;
  private final Clock clock;

  AuditIncidentScopeCaseReport(AuditIncidentScopeGrantRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /** The counts of {@code organizationId} in {@code period}, every purpose of the closed list. */
  public IncidentScopeCases report(UUID organizationId, ReportingPeriod period) {
    Instant now = clock.instant();
    LocalDate runningMonth = LocalDate.ofInstant(now, clock.getZone()).withDayOfMonth(1);
    LocalDate toMonth = period.to().withDayOfMonth(1);
    List<TransitionCount> rows =
        new ArrayList<>(
            repository.sumTransitions(organizationId, period.from(), toMonth, runningMonth));
    if (!runningMonth.isBefore(period.from()) && !runningMonth.isAfter(toMonth)) {
      rows.addAll(
          repository.countEndedBetween(
              organizationId, runningMonth.atStartOfDay(clock.getZone()).toInstant(), now));
    }
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
    return new IncidentScopeCases(period, purposes, NOTES);
  }

  private static CaseCount count(
      Map<String, Long> totals,
      AuditIncidentScopePurpose purpose,
      AuditIncidentScopeTransition transition) {
    return CaseCount.of(totals.getOrDefault(purpose.name() + "/" + transition.name(), 0L));
  }
}
