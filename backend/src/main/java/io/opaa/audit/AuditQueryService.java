package io.opaa.audit;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The single funnel every audit-log read goes through - the four access paths the specification
 * allows (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt),
 * plus the one personenbezogene exception ({@link #byIncidentScope}).
 *
 * <p><b>Self-logging.</b> Every method is wrapped by {@link #loggedAccess}, which runs {@link
 * AuditAccessGate}: role, Anlass and bounds are enforced there rather than by {@code @PreAuthorize}
 * on the controller - a role-based 403 thrown by a security interceptor would run before this class
 * and so be invisible to the self-log ("auch der abgewiesene Versuch erzeugt einen Eintrag").
 * {@link io.opaa.api.AuditController} therefore declares no {@code @PreAuthorize} on these
 * endpoints. Exactly one entry is written per call, whatever the outcome.
 *
 * <p><b>Transaction behaviour.</b> No method here opens or joins an ambient transaction; {@link
 * AuditEventRecorder#recordAuditLogAccess} carries its own {@code Propagation.NOT_SUPPORTED} so the
 * self-log entry survives regardless of whether a caller wraps this class in a transaction that
 * later rolls back.
 *
 * <p>Every method requires a non-null, non-inverted {@code from}/{@code to} no wider than {@link
 * #MAX_TIME_RANGE_DAYS} ("eine Abfrage ohne Zeitgrenze ist ein Vollabzug"); builds its own {@link
 * Pageable} with a fixed sort on {@code recordedAt}, a page size capped at {@link #MAX_PAGE_SIZE},
 * and rejects (rather than clamps) a page index beyond {@link #MAX_PAGE_INDEX}, so the total rows a
 * query can return across every page stays bounded. No method accepts a parameter that filters or
 * sorts by the acting person; {@code callerId} (every method's second parameter) never reaches a
 * {@code WHERE} clause, it only identifies who to attribute the self-log entry to.
 */
@Service
public class AuditQueryService {

  /**
   * @see AuditAccessGate#MAX_PAGE_SIZE
   */
  static final int MAX_PAGE_SIZE = AuditAccessGate.MAX_PAGE_SIZE;

  /**
   * @see AuditAccessGate#MAX_PAGE_INDEX
   */
  static final int MAX_PAGE_INDEX = AuditAccessGate.MAX_PAGE_INDEX;

  /**
   * @see AuditAccessGate#MAX_TIME_RANGE_DAYS
   */
  static final long MAX_TIME_RANGE_DAYS = AuditAccessGate.MAX_TIME_RANGE_DAYS;

  /**
   * Matches {@code audit_log.reason varchar(1000)} (migration 017) - the single source of this
   * bound; {@code DiagnosticContextLogQueryService} references this constant rather than copying
   * the number a third time.
   */
  public static final int MAX_REASON_LENGTH = AuditAccessGate.MAX_REASON_LENGTH;

  private static final Sort RECORDED_AT_ASC = Sort.by(Sort.Direction.ASC, "recordedAt");

  private final AuditLogRepository auditLogRepository;
  private final AuditIncidentScopeService incidentScopeService;
  private final AuditActorPseudonymService pseudonymService;
  private final AuditEventRecorder eventRecorder;
  private final AuditAccessGate gate;

  public AuditQueryService(
      AuditLogRepository auditLogRepository,
      AuditIncidentScopeService incidentScopeService,
      AuditActorPseudonymService pseudonymService,
      AuditEventRecorder eventRecorder,
      AuditAccessGate gate) {
    this.auditLogRepository = auditLogRepository;
    this.incidentScopeService = incidentScopeService;
    this.pseudonymService = pseudonymService;
    this.eventRecorder = eventRecorder;
    this.gate = gate;
  }

  /**
   * Access path "nach Objekt". Rejects {@link AuditObjectType#USER_ACCOUNT} outright: a {@code
   * USER_ACCOUNT} object's {@code object_id} is that person's pseudonym, the same pseudonym {@code
   * actorRef} carries on every one of that person's own actions elsewhere in the log. Without this
   * rejection, a caller could read off a pseudonym via {@link #byTimeRange} and then query by it
   * here, reconstructing exactly the excluded view "alle Ereignisse, bei denen Person X betroffen
   * war". That legitimate need is served instead by the freigegebene anlassbezogene Klärung ({@link
   * #byIncidentScope}) or the Rechtehistorie.
   */
  public Page<AuditLogEntry> byObject(
      UUID organizationId,
      UUID callerId,
      String reason,
      AuditObjectType objectType,
      String objectId,
      Instant from,
      Instant to,
      int page,
      int size) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", "by-object");
    scope.put("objectType", str(objectType));
    scope.put("objectId", objectId);
    scope.put("from", str(from));
    scope.put("to", str(to));
    return loggedAccess(
        organizationId,
        callerId,
        reason,
        scope,
        () -> {
          validateTimeRange(from, to);
          if (objectType == AuditObjectType.USER_ACCOUNT) {
            throw new IllegalArgumentException(
                "objectType USER_ACCOUNT ist über diesen Weg nicht abfragbar - object_id wäre"
                    + " hier dieselbe Pseudonymkennung, die anderswo actor_ref ist; die"
                    + " anlassbezogene Klärung oder die Rechtehistorie sind der zulässige Weg für"
                    + " diese Frage");
          }
          return auditLogRepository
              .findByOrganizationIdAndObjectTypeAndObjectIdAndRecordedAtBetween(
                  organizationId, objectType, objectId, from, to, pageable(page, size));
        });
  }

  /** Access path "nach Zeitraum". */
  public Page<AuditLogEntry> byTimeRange(
      UUID organizationId,
      UUID callerId,
      String reason,
      Instant from,
      Instant to,
      int page,
      int size) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", "by-time-range");
    scope.put("from", str(from));
    scope.put("to", str(to));
    return loggedAccess(
        organizationId,
        callerId,
        reason,
        scope,
        () -> {
          validateTimeRange(from, to);
          return auditLogRepository.findByOrganizationIdAndRecordedAtBetween(
              organizationId, from, to, pageable(page, size));
        });
  }

  /** Access path "nach Ereignisart". */
  public Page<AuditLogEntry> byEventType(
      UUID organizationId,
      UUID callerId,
      String reason,
      AuditEventType eventType,
      Instant from,
      Instant to,
      int page,
      int size) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", "by-event-type");
    scope.put("eventType", str(eventType));
    scope.put("from", str(from));
    scope.put("to", str(to));
    return loggedAccess(
        organizationId,
        callerId,
        reason,
        scope,
        () -> {
          validateTimeRange(from, to);
          return auditLogRepository.findByOrganizationIdAndEventTypeAndRecordedAtBetween(
              organizationId, eventType, from, to, pageable(page, size));
        });
  }

  /** Access path "nach Vorgang" (correlation_ref). */
  public Page<AuditLogEntry> byCorrelation(
      UUID organizationId,
      UUID callerId,
      String reason,
      String correlationRef,
      Instant from,
      Instant to,
      int page,
      int size) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", "by-correlation");
    scope.put("correlationRef", correlationRef);
    scope.put("from", str(from));
    scope.put("to", str(to));
    return loggedAccess(
        organizationId,
        callerId,
        reason,
        scope,
        () -> {
          validateTimeRange(from, to);
          return auditLogRepository.findByOrganizationIdAndCorrelationRefAndRecordedAtBetween(
              organizationId, correlationRef, from, to, pageable(page, size));
        });
  }

  /**
   * The one personenbezogene exception: every event whose actor is the approved grant's named
   * person, further bounded to a caller-requested time range that must lie entirely within the
   * grant's own scope - a request reaching outside it is rejected, not clamped (see {@link
   * AuditIncidentScopeGrant#covers}).
   *
   * <p>Looks the subject's pseudonym up, never mints one: unlike {@link
   * AuditActorPseudonymService#pseudonymFor}, a read must never have the side effect of creating a
   * re-identification row for a person who never triggered one themselves. If none exists, the
   * person has no entries in the log at all, so an empty page is correct.
   */
  public Page<AuditLogEntry> byIncidentScope(
      UUID organizationId,
      UUID callerId,
      String reason,
      UUID scopeId,
      Instant from,
      Instant to,
      int page,
      int size) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", "by-incident-scope");
    scope.put("scopeId", str(scopeId));
    scope.put("from", str(from));
    scope.put("to", str(to));
    return loggedAccess(
        organizationId,
        callerId,
        reason,
        scope,
        () -> {
          validateTimeRange(from, to);
          AuditIncidentScopeGrant grant =
              incidentScopeService.findApproved(organizationId, scopeId);
          if (!grant.covers(from, to)) {
            throw new IllegalArgumentException(
                "Der angefragte Zeitraum liegt außerhalb der freigegebenen Klärung");
          }
          Pageable pageable = pageable(page, size);
          return pseudonymService
              .findExistingPseudonym(grant.getSubjectUserId())
              .map(
                  pseudonym ->
                      auditLogRepository.findByOrganizationIdAndActorRefAndRecordedAtBetween(
                          organizationId, pseudonym.toString(), from, to, pageable))
              .orElseGet(() -> Page.empty(pageable));
        });
  }

  /**
   * The funnel's own entry: {@link AuditAccessGate} enforces role, Anlass and bounds, and this
   * method says what the resulting entry is - one {@code AUDIT_LOG_ACCESSED} row per call against
   * {@code audit_log} itself, whatever the outcome.
   */
  private <T> T loggedAccess(
      UUID organizationId,
      UUID callerId,
      String reason,
      Map<String, Object> scope,
      Supplier<T> query) {
    return gate.loggedAccess(
        organizationId,
        callerId,
        reason,
        (outcome, cappedReason) ->
            eventRecorder.recordAuditLogAccess(
                organizationId, callerId, scope, outcome, cappedReason),
        query);
  }

  private void validateTimeRange(Instant from, Instant to) {
    gate.validateTimeRange(from, to);
  }

  private Pageable pageable(int page, int size) {
    return gate.pageable(page, size, RECORDED_AT_ASC);
  }

  /**
   * Null-safe {@code toString()} for the {@code scope} maps above - {@link AuditEventRecorder}'s
   * {@code JsonMapper} carries no JSR-310 module, so an {@link Instant} (or any other non-plain
   * value) must already be a plain {@link String} by the time it reaches {@code toJson}.
   */
  private static String str(Object value) {
    return value == null ? null : value.toString();
  }
}
