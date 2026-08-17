package io.opaa.audit;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The single funnel every #393 revision read goes through - exactly the four access paths the
 * specification allows
 * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt), plus
 * the one personenbezogene exception. A single class rather than the query methods scattered across
 * callers on purpose: #394 ("Audit-Zugriff protokolliert sich selbst", not built here) adds
 * self-logging by wrapping the methods on this one class, not by finding every place that reads
 * {@code audit_log}.
 *
 * <p>Every method: requires a non-null, non-inverted {@code from}/{@code to} (mandatory time range,
 * per the specification - "eine Abfrage ohne Zeitgrenze ist ein Vollabzug"); builds its own {@link
 * Pageable} with a fixed sort on {@code recordedAt} and a page size capped at {@link
 * #MAX_PAGE_SIZE} regardless of what the caller asked for (bounded result set); accepts no
 * parameter that names or sorts by the acting person - {@code actorRef} never appears as an input
 * anywhere below except {@link #byIncidentScope}, and there it is resolved from the approved
 * grant's subject, not accepted from the caller.
 */
@Service
public class AuditQueryService {

  /** "Begrenzte Ergebnismenge" - a hard cap applied regardless of the requested page size. */
  static final int MAX_PAGE_SIZE = 200;

  private static final Sort RECORDED_AT_ASC = Sort.by(Sort.Direction.ASC, "recordedAt");

  private final AuditLogRepository auditLogRepository;
  private final AuditIncidentScopeService incidentScopeService;
  private final AuditActorPseudonymService pseudonymService;

  public AuditQueryService(
      AuditLogRepository auditLogRepository,
      AuditIncidentScopeService incidentScopeService,
      AuditActorPseudonymService pseudonymService) {
    this.auditLogRepository = auditLogRepository;
    this.incidentScopeService = incidentScopeService;
    this.pseudonymService = pseudonymService;
  }

  /** Access path "nach Objekt". */
  public Page<AuditLogEntry> byObject(
      UUID organizationId,
      AuditObjectType objectType,
      String objectId,
      Instant from,
      Instant to,
      int page,
      int size) {
    validateTimeRange(from, to);
    return auditLogRepository.findByOrganizationIdAndObjectTypeAndObjectIdAndRecordedAtBetween(
        organizationId, objectType, objectId, from, to, pageable(page, size));
  }

  /** Access path "nach Zeitraum". */
  public Page<AuditLogEntry> byTimeRange(
      UUID organizationId, Instant from, Instant to, int page, int size) {
    validateTimeRange(from, to);
    return auditLogRepository.findByOrganizationIdAndRecordedAtBetween(
        organizationId, from, to, pageable(page, size));
  }

  /** Access path "nach Ereignisart". */
  public Page<AuditLogEntry> byEventType(
      UUID organizationId, AuditEventType eventType, Instant from, Instant to, int page, int size) {
    validateTimeRange(from, to);
    return auditLogRepository.findByOrganizationIdAndEventTypeAndRecordedAtBetween(
        organizationId, eventType, from, to, pageable(page, size));
  }

  /** Access path "nach Vorgang" (correlation_ref). */
  public Page<AuditLogEntry> byCorrelation(
      UUID organizationId, String correlationRef, Instant from, Instant to, int page, int size) {
    validateTimeRange(from, to);
    return auditLogRepository.findByOrganizationIdAndCorrelationRefAndRecordedAtBetween(
        organizationId, correlationRef, from, to, pageable(page, size));
  }

  /**
   * The one personenbezogene exception: every event whose actor is the approved grant's named
   * person, further bounded to a caller-requested time range that must lie entirely within the
   * grant's own scope - a request reaching outside it is rejected, not clamped (see {@link
   * AuditIncidentScopeGrant#covers}).
   */
  public Page<AuditLogEntry> byIncidentScope(
      UUID organizationId, UUID scopeId, Instant from, Instant to, int page, int size) {
    validateTimeRange(from, to);
    AuditIncidentScopeGrant grant = incidentScopeService.findApproved(organizationId, scopeId);
    if (!grant.covers(from, to)) {
      throw new IllegalArgumentException(
          "Der angefragte Zeitraum liegt außerhalb der freigegebenen Klärung");
    }
    String subjectActorRef =
        pseudonymService.pseudonymFor(grant.getSubjectUserId(), organizationId).toString();
    return auditLogRepository.findByOrganizationIdAndActorRefAndRecordedAtBetween(
        organizationId, subjectActorRef, from, to, pageable(page, size));
  }

  private void validateTimeRange(Instant from, Instant to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("from und to sind Pflichtangaben");
    }
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("from darf nicht nach to liegen");
    }
  }

  private Pageable pageable(int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    return PageRequest.of(safePage, safeSize, RECORDED_AT_ASC);
  }
}
