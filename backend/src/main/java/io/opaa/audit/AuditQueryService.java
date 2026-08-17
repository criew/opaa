package io.opaa.audit;

import java.time.Duration;
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
      AuditObjectType objectType,
      String objectId,
      Instant from,
      Instant to,
      int page,
      int size) {
    validateTimeRange(from, to);
    if (objectType == AuditObjectType.USER_ACCOUNT) {
      throw new IllegalArgumentException(
          "objectType USER_ACCOUNT ist über diesen Weg nicht abfragbar - object_id waere hier"
              + " dieselbe Pseudonymkennung, die anderswo actor_ref ist; die anlassbezogene"
              + " Klaerung oder die Rechtehistorie (#238) sind der zulaessige Weg fuer diese Frage");
    }
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
   *
   * <p>Looks the subject's pseudonym up, it never mints one (#393 code review, finding 8): unlike
   * {@link AuditActorPseudonymService#pseudonymFor}, a read must never have the side effect of
   * creating a re-identification row for a person who never triggered one themselves - a person
   * with no audit activity of their own has no pseudonym yet, and this GET must not be what gives
   * them one. If none exists, the person has no entries in the log at all, so an empty page is the
   * correct, not merely convenient, answer.
   */
  public Page<AuditLogEntry> byIncidentScope(
      UUID organizationId, UUID scopeId, Instant from, Instant to, int page, int size) {
    validateTimeRange(from, to);
    AuditIncidentScopeGrant grant = incidentScopeService.findApproved(organizationId, scopeId);
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
}
