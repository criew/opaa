package io.opaa.audit;

import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * The bar and the bounds every revision access passes: the AUDITOR role, the mandatory Anlass, the
 * bounded time range and the bounded paging - enforced here rather than by {@code @PreAuthorize},
 * because a 403 thrown by a security interceptor would run before the funnel and so be invisible to
 * the entry the specification requires for the rejected attempt too. The entry itself is written by
 * the caller's {@code logEntry}, exactly once per call: {@link AuditQueryService} writes it against
 * {@code audit_log}, the Stichtagsauskunft (#1822) against the queried object.
 */
@Component
class AuditAccessGate {

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

  /** Matches {@code audit_log.reason varchar(1000)} (migration 017) - the single source. */
  static final int MAX_REASON_LENGTH = 1000;

  private static final String NOT_AUDITOR_MESSAGE =
      "Zugriff verweigert - der Zugriff auf Protokolldaten ist der AUDITOR-Rolle vorbehalten";

  private static final Logger log = LoggerFactory.getLogger(AuditAccessGate.class);

  private final UserRepository userRepository;

  AuditAccessGate(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  /**
   * Enforces role and Anlass, runs {@code query}, and lets {@code logEntry} write exactly one entry
   * either way - {@link AuditOutcome#SUCCESS} when {@code query} returns normally, otherwise the
   * outcome {@link AuditAccessOutcome} derives from the exception. The original exception always
   * propagates unchanged; a failure of the entry itself never replaces it.
   */
  <T> T loggedAccess(
      UUID organizationId,
      UUID callerId,
      String reason,
      BiConsumer<AuditOutcome, String> logEntry,
      Supplier<T> query) {
    try {
      requireAuditor(organizationId, callerId);
      requireReason(reason);
      T result = query.get();
      logEntry.accept(AuditOutcome.SUCCESS, capReason(reason));
      return result;
    } catch (RuntimeException ex) {
      try {
        logEntry.accept(AuditAccessOutcome.of(ex), capReason(reason));
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the self-log entry for a failed revision access - the failure is"
                + " still reported correctly, but this attempt is missing its audit_log entry",
            loggingFailure);
        ex.addSuppressed(loggingFailure);
      }
      throw ex;
    }
  }

  /**
   * The role check moved out of {@code @PreAuthorize} - see the class Javadoc. Looks the caller up
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

  /**
   * Bounds what the entry actually writes to {@code audit_log.reason varchar(1000)} - {@link
   * #requireReason} rejects an over-length reason only once {@link #requireAuditor} has passed, so
   * a denied non-AUDITOR attempt reaches the {@code catch} with an unchecked reason to record.
   */
  static String capReason(String reason) {
    return reason == null || reason.length() <= MAX_REASON_LENGTH
        ? reason
        : reason.substring(0, MAX_REASON_LENGTH);
  }

  void validateTimeRange(Instant from, Instant to) {
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

  /** The bounded page index and size every access path shares; {@code sort} is the path's own. */
  Pageable pageable(int page, int size, Sort sort) {
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
    return PageRequest.of(page, safeSize, sort);
  }
}
