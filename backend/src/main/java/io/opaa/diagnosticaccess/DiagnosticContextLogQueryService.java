package io.opaa.diagnosticaccess;

import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditAccessOutcome;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditQueryService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only three read paths into the diagnostic context protocol, and the shape of each is what
 * enforces Leitplanke (g):
 *
 * <ul>
 *   <li>{@link #findOwnEvents} - the Einsichtsrecht. Filters by the requesting person's own
 *       pseudonym, resolved here from their user id; no caller can name someone else.
 *   <li>{@link #findByTimeRange} - the Gesamtprotokoll, restricted to {@link SystemRole#AUDITOR}
 *       (Datenschutzbeauftragte, Personalvertretung; a Fachvorgesetzter is a {@code USER} or {@code
 *       SYSTEM_ADMIN} and reaches nothing here) and requiring a reason plus a time range of at most
 *       {@link #MAX_RANGE_DAYS} days. <b>It takes no target-person parameter at all</b>, so
 *       "Diagnosen zu Person X" cannot be asked - not restricted, not aggregated away, simply not
 *       expressible.
 *   <li>{@link #findSingleEvent} - one already-known entry, under the same AUDITOR bar, the same
 *       mandatory reason and its own audit_log record. It is the einzelfall- und anlassbezogene
 *       Auswertung Leitplanke (g) provides for, and the only path that publishes an entry's {@code
 *       permissionSnapshot}: as a list field it would be a per-person grouping key, one entry at a
 *       time it is not.
 * </ul>
 *
 * <p>There is deliberately no fourth method. No counting, grouping, sorting-by-target or export
 * variant exists in this class, and {@link DiagnosticContextLogRepository} offers nothing one could
 * be built on.
 */
@Service
public class DiagnosticContextLogQueryService {

  /** An anlassbezogene Einzelfallauswertung, not a standing report - hence a bounded window. */
  public static final int MAX_RANGE_DAYS = 31;

  private static final int MAX_PAGE_SIZE = 100;

  /** A UUID is 36 characters; anything longer is not one and is recorded only as far as this. */
  private static final int MAX_EVENT_ID_LENGTH = 64;

  private static final Logger log = LoggerFactory.getLogger(DiagnosticContextLogQueryService.class);

  private final DiagnosticContextLogRepository logRepository;
  private final AuditActorPseudonymService pseudonymService;
  private final UserRepository userRepository;
  private final AuditEventRecorder auditEventRecorder;

  public DiagnosticContextLogQueryService(
      DiagnosticContextLogRepository logRepository,
      AuditActorPseudonymService pseudonymService,
      UserRepository userRepository,
      AuditEventRecorder auditEventRecorder) {
    this.logRepository = logRepository;
    this.pseudonymService = pseudonymService;
    this.userRepository = userRepository;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * The caller's own entries - no role, no reason and no approval needed ("ohne Antragsweg und ohne
   * Beteiligung Dritter"). Empty as long as nobody has ever assumed this person's context, which is
   * also the case when they have no pseudonym yet.
   */
  @Transactional(readOnly = true)
  public Page<OwnDiagnosticContextEvent> findOwnEvents(CurrentUser caller, int page, int size) {
    Optional<UUID> pseudonym = pseudonymService.findExistingPseudonym(caller.id());
    if (pseudonym.isEmpty()) {
      return Page.empty(pageRequest(page, size));
    }
    Page<DiagnosticContextLogEntry> entries =
        logRepository.findOwnEntries(
            caller.organizationId(),
            DiagnosticTargetKind.USER,
            pseudonym.get().toString(),
            pageRequest(page, size));
    return new PageImpl<>(
        entries.getContent().stream().map(this::toOwnEvent).toList(),
        entries.getPageable(),
        entries.getTotalElements());
  }

  /**
   * The Gesamtprotokoll for the named Stellen. Records its own invocation in the audit trail,
   * including a rejected one - the role check happens here rather than as an annotation exactly so
   * a denial is recordable, and it is recorded through {@link
   * AuditEventRecorder#recordAuditLogAccess}, whose {@code Propagation.NOT_SUPPORTED} keeps the
   * entry from being rolled back by the very exception that rejects the call. A rejected attempt is
   * recorded as {@code DENIED}, a query that fails after the checks passed as {@code FAILURE} -
   * {@link AuditAccessOutcome} draws the line for both classes. This method holds no transaction of
   * its own: it issues one query and needs none.
   */
  public Page<DiagnosticContextLogEntry> findByTimeRange(
      CurrentUser caller, Instant from, Instant to, String reason, int page, int size) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", "diagnostic-context-events");
    scope.put("from", from == null ? null : from.toString());
    scope.put("to", to == null ? null : to.toString());
    try {
      if (caller.systemRole() != SystemRole.AUDITOR) {
        throw new AccessDeniedException(
            "Das Gesamtprotokoll steht nur den benannten Stellen offen");
      }
      requireValidReason(reason);
      if (from == null || to == null || !to.isAfter(from)) {
        throw new ValidationException("Der Zeitraum ist unvollständig oder leer");
      }
      if (Duration.between(from, to).toDays() > MAX_RANGE_DAYS) {
        throw new ValidationException(
            "Der Zeitraum darf höchstens " + MAX_RANGE_DAYS + " Tage umfassen");
      }
      Page<DiagnosticContextLogEntry> result =
          logRepository.findByTimeRange(caller.organizationId(), from, to, pageRequest(page, size));
      recordProtocolAccess(caller, scope, reason, AuditOutcome.SUCCESS);
      return result;
    } catch (RuntimeException failed) {
      // Mirrors AuditQueryService#loggedAccess: the entry is best-effort on top of the failure,
      // never a precondition for reporting it correctly.
      try {
        recordProtocolAccess(caller, scope, reason, AuditAccessOutcome.of(failed));
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the entry for a failed Gesamtprotokoll access - the failure is"
                + " still reported correctly, but this attempt is missing its audit_log entry",
            loggingFailure);
        failed.addSuppressed(loggingFailure);
      }
      throw failed;
    }
  }

  /**
   * One entry by its own id, for an evaluation that already knows which entry it is about. Same
   * bar, same recording and the same best-effort entry as {@link #findByTimeRange}, and it holds no
   * transaction of its own for the same reason. An unknown, unreadable or organization-foreign id
   * is a rejected attempt, not a malfunction, and is therefore recorded as {@code DENIED} - the id
   * is taken as text and parsed here so that a malformed one reaches this method at all, the same
   * reason {@code DiagnosticContextLogController} binds {@code reason} as optional.
   *
   * @throws io.opaa.common.NotFoundException if no entry of the caller's organization carries this
   *     id - an entry of a foreign organization is not distinguishable from an unknown one here
   */
  public DiagnosticContextLogEntry findSingleEvent(
      CurrentUser caller, String eventId, String reason) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", "diagnostic-context-events/{eventId}");
    scope.put("eventId", abbreviated(eventId));
    try {
      if (caller.systemRole() != SystemRole.AUDITOR) {
        throw new AccessDeniedException(
            "Das Gesamtprotokoll steht nur den benannten Stellen offen");
      }
      requireValidReason(reason);
      UUID requested = requireEventId(eventId);
      DiagnosticContextLogEntry entry =
          logRepository
              .findSingleEntry(caller.organizationId(), requested)
              .orElseThrow(() -> new NotFoundException("Protokolleintrag nicht gefunden"));
      recordProtocolAccess(caller, scope, reason, AuditOutcome.SUCCESS);
      return entry;
    } catch (RuntimeException failed) {
      try {
        recordProtocolAccess(caller, scope, reason, AuditAccessOutcome.of(failed));
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the entry for a failed single-entry access - the failure is still"
                + " reported correctly, but this attempt is missing its audit_log entry",
            loggingFailure);
        failed.addSuppressed(loggingFailure);
      }
      throw failed;
    }
  }

  /**
   * The one place a pseudonym is resolved back to a person, and only ever the acting one for the
   * affected person's own view - Leitplanke (h) requires "von wem". A person whose account is gone
   * shows as unnamed rather than failing the whole page.
   */
  private OwnDiagnosticContextEvent toOwnEvent(DiagnosticContextLogEntry entry) {
    String actorDisplayName =
        parseUuid(entry.getActorRef())
            .flatMap(pseudonymService::findUserByPseudonym)
            .flatMap(userRepository::findById)
            .map(user -> user.getDisplayName())
            .orElse(null);
    return new OwnDiagnosticContextEvent(
        entry.getRecordedAt(), actorDisplayName, entry.getJustification());
  }

  /**
   * A missing or too-long reason is a rejected attempt, not a malfunction - an overlong one would
   * otherwise reach {@link AuditEventRecorder#recordAuditLogAccess} and fail the write of {@code
   * audit_log.reason varchar(1000)} itself, leaving the attempt unrecorded.
   */
  private static void requireValidReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new ValidationException("Für die Einsicht ist ein Anlass anzugeben");
    }
    if (reason.length() > AuditQueryService.MAX_REASON_LENGTH) {
      throw new ValidationException(
          "Der Anlass ist zu lang - maximal " + AuditQueryService.MAX_REASON_LENGTH + " Zeichen");
    }
  }

  /** Parsed here, not bound by Spring MVC - see {@link #findSingleEvent}. */
  private static UUID requireEventId(String eventId) {
    if (eventId == null || eventId.isBlank()) {
      throw new ValidationException("Der Protokolleintrag ist nicht benannt");
    }
    return parseUuid(eventId)
        .orElseThrow(
            () -> new ValidationException("Die Kennung des Protokolleintrags ist unlesbar"));
  }

  /** Bounds what an unparsed, caller-supplied id can put into the audit entry's scope. */
  private static String abbreviated(String eventId) {
    if (eventId == null) {
      return null;
    }
    return eventId.length() <= MAX_EVENT_ID_LENGTH
        ? eventId
        : eventId.substring(0, MAX_EVENT_ID_LENGTH);
  }

  /**
   * Bounds what an over-length {@code reason} can put into the entry - {@code audit_log.reason} is
   * {@code varchar(1000)}, so a raw reason exceeding {@link AuditQueryService#MAX_REASON_LENGTH}
   * would make even the rejected-attempt entry itself fail to write, defeating {@link
   * #requireValidReason}'s point.
   */
  private void recordProtocolAccess(
      CurrentUser caller, Map<String, Object> scope, String reason, AuditOutcome outcome) {
    String bounded =
        reason == null || reason.length() <= AuditQueryService.MAX_REASON_LENGTH
            ? reason
            : reason.substring(0, AuditQueryService.MAX_REASON_LENGTH);
    auditEventRecorder.recordAuditLogAccess(
        caller.organizationId(), caller.id(), scope, outcome, bounded);
  }

  private static Optional<UUID> parseUuid(String value) {
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException notAUuid) {
      return Optional.empty();
    }
  }

  private static PageRequest pageRequest(int page, int size) {
    return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
  }
}
