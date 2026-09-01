package io.opaa.diagnosticaccess;

import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.DiagnosticTargetKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
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
 * The only two read paths into the diagnostic context protocol, and the shape of both is what
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
 * </ul>
 *
 * <p>There is deliberately no third method. No counting, grouping, sorting-by-target or export
 * variant exists in this class, and {@link DiagnosticContextLogRepository} offers nothing one could
 * be built on.
 */
@Service
public class DiagnosticContextLogQueryService {

  /** An anlassbezogene Einzelfallauswertung, not a standing report - hence a bounded window. */
  public static final int MAX_RANGE_DAYS = 31;

  private static final int MAX_PAGE_SIZE = 100;

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
   * entry from being rolled back by the very exception that rejects the call. This method holds no
   * transaction of its own: it issues one query and needs none.
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
      if (reason == null || reason.isBlank()) {
        throw new ValidationException("Für die Einsicht ist ein Anlass anzugeben");
      }
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
    } catch (RuntimeException rejected) {
      // Mirrors AuditQueryService#loggedAccess: the DENIED entry is best-effort on top of the
      // rejection, never a precondition for reporting it correctly.
      try {
        recordProtocolAccess(caller, scope, reason, AuditOutcome.DENIED);
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the DENIED entry for a rejected Gesamtprotokoll access - the"
                + " rejection is still reported correctly, but this attempt is missing its"
                + " audit_log entry",
            loggingFailure);
        rejected.addSuppressed(loggingFailure);
      }
      throw rejected;
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

  private void recordProtocolAccess(
      CurrentUser caller, Map<String, Object> scope, String reason, AuditOutcome outcome) {
    auditEventRecorder.recordAuditLogAccess(
        caller.organizationId(), caller.id(), scope, outcome, reason);
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
