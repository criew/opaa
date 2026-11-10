package io.opaa.query.runlog;

import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditAccessOutcome;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditQueryService;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The one way into the step log and the sandbox log: the system administration of the run's own
 * organization, for troubleshooting, with exactly one run id and a stated reason. Every call writes
 * {@code AUDIT_LOG_ACCESSED} with its own access path - a refused one as {@code DENIED}, a failed
 * one as {@code FAILURE}, through {@link AuditEventRecorder#recordAuditLogAccess}, which outlives
 * the exception that refuses the call. A run of another organization reads as unknown.
 */
@Service
public class AgentRunLogQueryService {

  static final String ACCESS_PATH = "agent-runs/{runId}/log";

  private static final Logger log = LoggerFactory.getLogger(AgentRunLogQueryService.class);

  private final AgentRunLogReader reader;
  private final AuditEventRecorder auditEventRecorder;

  public AgentRunLogQueryService(AgentRunLogReader reader, AuditEventRecorder auditEventRecorder) {
    this.reader = reader;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * @throws io.opaa.common.AccessDeniedException for anyone but the system administration.
   * @throws NotFoundException when the logs hold nothing of this run in the caller's organization.
   */
  public AgentRunLog findRun(CurrentUser caller, String runId, String reason) {
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accessPath", ACCESS_PATH);
    scope.put("objectType", "AGENT_RUN");
    scope.put("objectId", abbreviated(runId));
    try {
      caller.requireSystemAdmin("Das Schrittprotokoll sieht nur die Systemverwaltung ein");
      requireValidReason(reason);
      UUID run = requireRunId(runId);
      List<AgentStepLogEntry> steps = reader.steps(caller.organizationId(), run);
      List<AgentSandboxLogEntry> sandboxes = reader.sandboxes(caller.organizationId(), run);
      if (steps.isEmpty() && sandboxes.isEmpty()) {
        throw new NotFoundException("Zu diesem Lauf gibt es keine Protokolleinträge");
      }
      record(caller, scope, reason, AuditOutcome.SUCCESS);
      return new AgentRunLog(run, steps, sandboxes);
    } catch (RuntimeException failed) {
      try {
        record(caller, scope, reason, AuditAccessOutcome.of(failed));
      } catch (RuntimeException loggingFailure) {
        log.error(
            "Failed to write the entry for a failed step log access - the failure is still"
                + " reported correctly, but this attempt is missing its audit_log entry",
            loggingFailure);
        failed.addSuppressed(loggingFailure);
      }
      throw failed;
    }
  }

  private void record(
      CurrentUser caller, Map<String, Object> scope, String reason, AuditOutcome outcome) {
    String bounded =
        reason == null || reason.length() <= AuditQueryService.MAX_REASON_LENGTH
            ? reason
            : reason.substring(0, AuditQueryService.MAX_REASON_LENGTH);
    auditEventRecorder.recordAuditLogAccess(
        caller.organizationId(), caller.id(), scope, outcome, bounded);
  }

  private static void requireValidReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new ValidationException("Für die Einsicht ist ein Anlass anzugeben");
    }
    if (reason.length() > AuditQueryService.MAX_REASON_LENGTH) {
      throw new ValidationException(
          "Der Anlass ist zu lang - maximal " + AuditQueryService.MAX_REASON_LENGTH + " Zeichen");
    }
  }

  private static UUID requireRunId(String runId) {
    if (runId == null || runId.isBlank()) {
      throw new ValidationException("Der Lauf ist nicht benannt");
    }
    try {
      return UUID.fromString(runId.strip());
    } catch (IllegalArgumentException e) {
      throw new NotFoundException("Zu diesem Lauf gibt es keine Protokolleinträge");
    }
  }

  /** The run id as the audit entry keeps it: bounded, since a malformed one is caller text. */
  private static String abbreviated(String runId) {
    if (runId == null) {
      return null;
    }
    return runId.length() <= 36 ? runId : runId.substring(0, 36);
  }
}
