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
 * The single funnel every #393 revision read goes through - exactly the four access paths the
 * specification allows
 * (docs/features/security-and-compliance.md#zugriffswege-was-es-gibt-und-was-es-nicht-gibt), plus
 * the one personenbezogene exception. A single class rather than the query methods scattered across
 * callers on purpose - and, since #394, the one seam self-logging hooks into rather than finding
 * every place that reads {@code audit_log}.
 *
 * <p><b>#394 self-logging.</b> Every method below is wrapped by {@link #loggedAccess}: it enforces
 * the AUDITOR role and the mandatory {@code reason} <em>itself</em>, rather than relying solely on
 * {@code @PreAuthorize} on the controller. That is a deliberate departure from the more common
 * annotation-only pattern elsewhere in this codebase - {@code @PreAuthorize} throws {@code
 * AccessDeniedException} from a security interceptor <em>before</em> the controller method body
 * (and therefore before this class) ever runs, which would make a role-based 403 invisible to the
 * one class the specification requires to log it ("auch der abgewiesene Versuch erzeugt einen
 * Eintrag"). {@link io.opaa.api.AuditController} therefore no longer declares {@code @PreAuthorize}
 * on any of the five read endpoints below; the role check happens here, where it can be logged, and
 * nowhere else needs to duplicate it.
 *
 * <p>{@link #loggedAccess} logs exactly once per call, on every exit path: {@link AuditOutcome#
 * SUCCESS} if the wrapped query completes, {@link AuditOutcome#DENIED} if anything it does throws a
 * {@link RuntimeException} - a missing/blank {@code reason}, a caller who is not (or no longer) an
 * AUDITOR, an invalid or too-wide time range, a page index beyond the bound, {@code objectType ==
 * USER_ACCOUNT} on {@link #byObject}, or an incident scope that is not approved, expired, or does
 * not cover the requested range on {@link #byIncidentScope}. The original exception is always
 * rethrown after logging - the caller still sees exactly the same failure it would without #394,
 * even if writing the {@code DENIED} entry itself fails (PR #450 review, finding 2): that failure
 * is captured via {@code addSuppressed} and logged at {@code error}, never allowed to replace or
 * hide the original rejection.
 *
 * <p><b>Chosen transaction behaviour.</b> No method in this class opens or joins an ambient
 * transaction of its own - a GET request in this codebase runs with no surrounding
 * {@code @Transactional} (see {@link io.opaa.api.AuditController}, which declares none), so under
 * today's only caller, {@link AuditEventRecorder#recordAuditLogAccess} - which bottoms out in
 * {@link AuditLogService#record}, a plain {@code save()} - opens and commits its own transaction
 * immediately, on the call, regardless of what this method does afterwards (including rethrowing
 * the very exception that triggered a {@code DENIED} entry). {@link
 * AuditEventRecorder#recordAuditLogAccess} additionally carries its own {@code
 * Propagation.NOT_SUPPORTED} (PR #450 review, finding 5) precisely so that guarantee does not rest
 * on "and no future caller ever wraps this in a transaction either" - see that method's Javadoc for
 * the full reasoning, including why {@code NOT_SUPPORTED} rather than {@code REQUIRES_NEW} was
 * chosen against the three separate-transaction incidents the developer role contract's
 * Transaktionen section warns against (#280, #297, #299). Verified by {@code
 * AuditQueryServiceIntegrationTest} against a real Postgres database with a real transaction
 * manager, not a mocked one: a rejected query leaves its {@code DENIED} entry behind both with no
 * ambient transaction at all, and when deliberately embedded in one that later rolls back.
 *
 * <p>Every method: requires a non-null, non-inverted {@code from}/{@code to} no wider than {@link
 * #MAX_TIME_RANGE_DAYS} (mandatory, bounded time range, per the specification - "eine Abfrage ohne
 * Zeitgrenze ist ein Vollabzug"; an unbounded-but-technically-timestamped range is the same thing
 * under a different name - #393 code review, finding 3); builds its own {@link Pageable} with a
 * fixed sort on {@code recordedAt} and a page size capped at {@link #MAX_PAGE_SIZE} regardless of
 * what the caller asked for, and rejects (rather than silently clamping) a page index beyond {@link
 * #MAX_PAGE_INDEX} - the same "abgewiesen, nicht gekappt" principle {@link #byIncidentScope}
 * already applies to a time range reaching outside its grant, so the total rows one query (across
 * every page it is willing to page through) can return is bounded, not merely "bounded per page"
 * (#393 re-review, nit 3); accepts no parameter that names or sorts by the acting person - {@code
 * actorRef} never appears as an input anywhere below except {@link #byIncidentScope}, and there it
 * is resolved from the approved grant's subject, not accepted from the caller. {@link #byObject}
 * additionally rejects {@link AuditObjectType#USER_ACCOUNT} - see its own Javadoc.
 *
 * <p>{@code callerId} (every method's second parameter) is deliberately not named with "actor" or
 * "person" in it, and deliberately not a query filter: {@code
 * AuditQueryServiceIntegrationTest#noAccessPathAcceptsOrSortsByActor} still holds - {@code
 * callerId} never reaches a {@code WHERE} clause anywhere in this class, it only identifies who to
 * attribute the #394 self-log entry to. It is the same requirement every other {@link
 * AuditEventRecorder} caller in this codebase already has (an {@code actorUserId} to pseudonymise),
 * just newly required here because, until #394, nothing in this class ever wrote an audit entry
 * itself.
 */
@Service
public class AuditQueryService {

  /** "Begrenzte Ergebnismenge" - a hard cap applied regardless of the requested page size. */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * Bounds how many pages a single query can page through, so {@link #MAX_PAGE_SIZE} bounds a
   * single page but not the whole query (#393 code review, finding 3: without this, {@code
   * page=0..n} against a wide-open time range turned "bounded per page" into an unbounded
   * full-extract in slices). 50 - a working month's worth of anlassbezogene review at 200 rows
   * each, not a data-warehouse export; page indices are 0-based, so pages 0..49 are usable. A
   * request beyond it is rejected with 400 (#393 re-review, nit 3), not silently clamped to the
   * last usable page - clamping would make a caller re-reading page 50 believe they are still
   * making progress through the result set when they are actually rereading page 49 forever.
   */
  static final int MAX_PAGE_INDEX = 49;

  /**
   * The mandatory time range's maximum width (#393 code review, finding 3): 92 days, roughly one
   * quarter - wide enough for the "alle Rechteänderungen zwischen dem 1. und dem 31. März"-style
   * queries the specification's own examples use, but not wide enough to serve as a disguised
   * full-history extract in a handful of calls. Revision works anlassbezogen against a bounded
   * window, not as a bulk data pull; a genuinely multi-year review chains several bounded calls,
   * each auditable on its own.
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
   * Access path "nach Objekt". Rejects {@link AuditObjectType#USER_ACCOUNT} outright (#393 code
   * review, finding 2): a {@code USER_ACCOUNT} object's {@code object_id} is that person's
   * pseudonym (see {@code UserService#updateRole}), the exact same pseudonym {@code actorRef}
   * carries on every one of that person's own actions elsewhere in the log. Without this rejection,
   * {@code by-time-range} (to read off a pseudonym from {@code actorRef}) followed by {@code
   * by-object?objectType=USER_ACCOUNT&objectId=<that pseudonym>} reconstructs exactly the excluded
   * view "alle Ereignisse, bei denen Person X betroffen war" - object_id would be an input field
   * carrying the same value actor_ref is only ever allowed to be an output of. The legitimate need
   * behind a {@code USER_ACCOUNT}-scoped question is served by {@link #byTimeRange} / {@link
   * #byEventType} (no person focus) or, for a genuinely person-scoped question, by the freigegebene
   * anlassbezogene Klärung ({@link #byIncidentScope}) or the Rechtehistorie (#238) - never by this
   * path.
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
                "objectType USER_ACCOUNT ist über diesen Weg nicht abfragbar - object_id waere"
                    + " hier dieselbe Pseudonymkennung, die anderswo actor_ref ist; die"
                    + " anlassbezogene Klaerung oder die Rechtehistorie (#238) sind der zulaessige"
                    + " Weg fuer diese Frage");
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
   * <p>Looks the subject's pseudonym up, it never mints one (#393 code review, finding 8): unlike
   * {@link AuditActorPseudonymService#pseudonymFor}, a read must never have the side effect of
   * creating a re-identification row for a person who never triggered one themselves - a person
   * with no audit activity of their own has no pseudonym yet, and this GET must not be what gives
   * them one. If none exists, the person has no entries in the log at all, so an empty page is the
   * correct, not merely convenient, answer.
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
   * see the class Javadoc), runs {@code query}, and writes exactly one #394 self-log entry either
   * way - {@link AuditOutcome#SUCCESS} if {@code query} returns normally, {@link AuditOutcome#
   * DENIED} if anything above (the role check, the reason check, or {@code query} itself, e.g. an
   * invalid time range) throws. The original exception always propagates unchanged after logging.
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
      // PR #450 review, finding 2: recordAuditLogAccess itself can throw (a lost connection, or
      // the "no partition of relation audit_log found for row" case this package's own migration
      // comments warn about once the fixed 017 partition horizon runs out) - that must never
      // replace the original rejection (ex). A non-AUDITOR caller has to see AccessDeniedException
      // and its 403, not an unrelated 500 from the logging attempt; the DENIED entry is best-effort
      // on top of the rejection, never a precondition for reporting it correctly. The logging
      // failure itself must not vanish silently either: it is attached to ex via addSuppressed
      // (visible in any stack trace ex is ever logged with) and logged here at error level in its
      // own right, since ex may propagate all the way to a generic 4xx/5xx handler that never logs
      // suppressed exceptions.
      try {
        eventRecorder.recordAuditLogAccess(
            organizationId, callerId, scope, AuditOutcome.DENIED, reason);
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the #394 DENIED self-log entry for a rejected audit_log access -"
                + " the original rejection is still reported correctly, but this attempt is"
                + " missing its own audit_log entry",
            loggingFailure);
        ex.addSuppressed(loggingFailure);
      }
      throw ex;
    }
  }

  /**
   * The role check {@code @PreAuthorize("hasRole('AUDITOR')")} would otherwise perform on the
   * controller - moved here so a denial can be logged (see the class Javadoc). Looks the caller up
   * by id scoped to {@code organizationId} rather than trusting a bare id, the same tenant boundary
   * {@link AuditIncidentScopeService#request} already applies to its subject.
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
          "reason ist ein Pflichtfeld fuer den Zugriff auf Protokolldaten - eine Abfrage ohne"
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
              + " Tage je Abfrage; ein groesserer Bedarf wird durch mehrere aufeinanderfolgende"
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
              + " je Abfrage; ein groesserer Bedarf wird durch mehrere aufeinanderfolgende"
              + " Abfragen mit engerem Zeitraum abgedeckt, nicht durch eine einzelne Seite ohne"
              + " Tiefenbegrenzung");
    }
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    return PageRequest.of(page, safeSize, RECORDED_AT_ASC);
  }

  /**
   * Null-safe {@code toString()} for the {@code scope} maps above - {@link AuditEventRecorder}'s
   * {@code JsonMapper} carries no JSR-310 module, so an {@link Instant} (or any other non-plain
   * value) must already be a plain {@link String} by the time it reaches {@code toJson}, the same
   * way every other {@link AuditEventRecorder} caller in this codebase pre-stringifies its own
   * before/after values (e.g. {@code role.name()} in {@code UserService#updateRole}).
   */
  private static String str(Object value) {
    return value == null ? null : value.toString();
  }
}
