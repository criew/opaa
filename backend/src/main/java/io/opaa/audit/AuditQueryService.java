package io.opaa.audit;

import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * The single funnel every audit-log read goes through - the four access paths the specification
 * allows (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt),
 * plus the one personenbezogene exception ({@link #byIncidentScope}).
 *
 * <p><b>Self-logging.</b> Every method is wrapped by {@link #loggedAccess}: it enforces the AUDITOR
 * role and the mandatory {@code reason} itself, rather than relying on {@code @PreAuthorize} on the
 * controller - a role-based 403 thrown by a security interceptor would run before this class and so
 * be invisible to the self-log ("auch der abgewiesene Versuch erzeugt einen Eintrag"). {@link
 * io.opaa.api.AuditController} therefore declares no {@code @PreAuthorize} on these endpoints.
 * {@link #loggedAccess} logs exactly once per call - {@link AuditOutcome#SUCCESS} if the query
 * completes, {@link AuditOutcome#DENIED} if anything it does throws - and always rethrows the
 * original exception unchanged, even if writing the {@code DENIED} entry itself fails.
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

  /** "Begrenzte Ergebnismenge" - a hard cap applied regardless of the requested page size. */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * Bounds how many pages a single query can page through, so {@link #MAX_PAGE_SIZE} bounds a
   * single page but not the whole query. A request beyond it is rejected with 400, not silently
   * clamped to the last usable page - clamping would let a caller re-reading the last page believe
   * they are still making progress. Page indices are 0-based, so pages 0..49 are usable.
   */
  static final int MAX_PAGE_INDEX = 49;

  /**
   * The mandatory time range's maximum width: 92 days, roughly one quarter. Revision works
   * anlassbezogen against a bounded window, not as a bulk data pull; a genuinely multi-year review
   * chains several bounded calls, each auditable on its own.
   */
  static final long MAX_TIME_RANGE_DAYS = 92;

  /** Matches {@code audit_log.reason varchar(1000)} (migration 017). */
  static final int MAX_REASON_LENGTH = 1000;

  private static final String NOT_AUDITOR_MESSAGE =
      "Zugriff verweigert - der Zugriff auf Protokolldaten ist der AUDITOR-Rolle vorbehalten";

  private static final Sort RECORDED_AT_ASC = Sort.by(Sort.Direction.ASC, "recordedAt");

  private static final Logger log = LoggerFactory.getLogger(AuditQueryService.class);

  private final AuditLogRepository auditLogRepository;
  private final AuditIncidentScopeService incidentScopeService;
  private final AuditActorPseudonymService pseudonymService;
  private final AuditEventRecorder eventRecorder;
  private final UserRepository userRepository;

  public AuditQueryService(
      AuditLogRepository auditLogRepository,
      AuditIncidentScopeService incidentScopeService,
      AuditActorPseudonymService pseudonymService,
      AuditEventRecorder eventRecorder,
      UserRepository userRepository) {
    this.auditLogRepository = auditLogRepository;
    this.incidentScopeService = incidentScopeService;
    this.pseudonymService = pseudonymService;
    this.eventRecorder = eventRecorder;
    this.userRepository = userRepository;
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
   * Enforces the AUDITOR role and the mandatory {@code reason} (both here, not on the controller -
   * see the class Javadoc), runs {@code query}, and writes exactly one self-log entry either way -
   * {@link AuditOutcome#SUCCESS} if {@code query} returns normally, {@link AuditOutcome#DENIED} if
   * anything above throws. The original exception always propagates unchanged after logging.
   */
  private <T> T loggedAccess(
      UUID organizationId,
      UUID callerId,
      String reason,
      Map<String, Object> scope,
      Supplier<T> query) {
    try {
      requireAuditor(organizationId, callerId);
      requireReason(reason);
      T result = query.get();
      eventRecorder.recordAuditLogAccess(
          organizationId, callerId, scope, AuditOutcome.SUCCESS, reason);
      return result;
    } catch (RuntimeException ex) {
      // recordAuditLogAccess itself can throw; that must never replace the original rejection
      // (ex) - the DENIED entry is best-effort on top of it, never a precondition for reporting
      // it correctly. A logging failure is attached via addSuppressed and logged here, since ex
      // may propagate to a handler that never logs suppressed exceptions.
      try {
        eventRecorder.recordAuditLogAccess(
            organizationId, callerId, scope, AuditOutcome.DENIED, reason);
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the DENIED self-log entry for a rejected audit_log access - the"
                + " rejection is still reported correctly, but this attempt is missing its"
                + " audit_log entry",
            loggingFailure);
        ex.addSuppressed(loggingFailure);
      }
      throw ex;
    }
  }

  /**
   * The role check {@code @PreAuthorize("hasRole('AUDITOR')")} would otherwise perform on the
   * controller - moved here so a denial can be logged (see the class Javadoc). Looks the caller up
   * by id scoped to {@code organizationId} rather than trusting a bare id.
   */
  private void requireAuditor(UUID organizationId, UUID callerId) {
    User caller =
        userRepository
            .findByIdAndOrganizationId(callerId, organizationId)
            .orElseThrow(() -> new AccessDeniedException(NOT_AUDITOR_MESSAGE));
    if (caller.getSystemRole() != SystemRole.AUDITOR) {
      throw new AccessDeniedException(NOT_AUDITOR_MESSAGE);
    }
  }

  /**
   * "Der Anlass ist bei diesen Einträgen ein Pflichtfeld; eine Abfrage ohne Anlass wird abgewiesen"
   * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt).
   */
  private void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException(
          "reason ist ein Pflichtfeld für den Zugriff auf Protokolldaten - eine Abfrage ohne"
              + " Anlass wird abgewiesen");
    }
    if (reason.length() > MAX_REASON_LENGTH) {
      throw new IllegalArgumentException(
          "reason ist zu lang - maximal " + MAX_REASON_LENGTH + " Zeichen");
    }
  }

  private void validateTimeRange(Instant from, Instant to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("from und to sind Pflichtangaben");
    }
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("from darf nicht nach to liegen");
    }
    if (Duration.between(from, to).toDays() > MAX_TIME_RANGE_DAYS) {
      throw new IllegalArgumentException(
          "Der Zeitraum ist zu weit gefasst - maximal "
              + MAX_TIME_RANGE_DAYS
              + " Tage je Abfrage; ein größerer Bedarf wird durch mehrere aufeinanderfolgende"
              + " Abfragen abgedeckt, nicht durch eine einzelne unbegrenzte");
    }
  }

  private Pageable pageable(int page, int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page darf nicht negativ sein");
    }
    if (page > MAX_PAGE_INDEX) {
      throw new IllegalArgumentException(
          "page ist zu tief - maximal Seite "
              + MAX_PAGE_INDEX
              + " je Abfrage; ein größerer Bedarf wird durch mehrere aufeinanderfolgende"
              + " Abfragen mit engerem Zeitraum abgedeckt, nicht durch eine einzelne Seite ohne"
              + " Tiefenbegrenzung");
    }
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    return PageRequest.of(page, safeSize, RECORDED_AT_ASC);
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
