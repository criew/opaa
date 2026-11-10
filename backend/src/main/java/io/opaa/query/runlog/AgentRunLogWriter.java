package io.opaa.query.runlog;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends to the step log and the sandbox log. A failed write is logged and dropped: the log serves
 * troubleshooting and never decides whether a run or its answer succeeds. The application account
 * can only append to both tables (ADR-0015); the time is the database's.
 */
@Component
public class AgentRunLogWriter {

  private static final Logger log = LoggerFactory.getLogger(AgentRunLogWriter.class);

  static final int MAX_NAME_CHARS = 64;
  static final int MAX_CODE_CHARS = 30;

  private final JdbcTemplate jdbcTemplate;

  public AgentRunLogWriter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Appends the steps of one run. */
  public void writeSteps(UUID organizationId, UUID runId, List<AgentStepLogEntry> steps) {
    if (steps.isEmpty()) {
      return;
    }
    try {
      jdbcTemplate.batchUpdate(
          "INSERT INTO agent_step_log (run_id, step_index, organization_id, tool, status,"
              + " hit_count, duration_ms, abort_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          steps,
          steps.size(),
          (statement, step) -> {
            statement.setObject(1, runId);
            statement.setInt(2, step.stepIndex());
            statement.setObject(3, organizationId);
            statement.setString(4, bounded(step.tool(), MAX_NAME_CHARS));
            statement.setString(5, bounded(step.status(), MAX_CODE_CHARS));
            statement.setObject(6, step.hitCount());
            statement.setInt(7, (int) Math.min(Integer.MAX_VALUE, Math.max(0, step.durationMs())));
            statement.setString(8, bounded(step.abortReason(), MAX_CODE_CHARS));
          });
    } catch (DataAccessException e) {
      log.warn("Agent run {}: the step log could not be written", runId, e);
    }
  }

  /** Appends one sandbox of a run. */
  public void writeSandbox(UUID organizationId, UUID runId, AgentSandboxLogEntry sandbox) {
    try {
      jdbcTemplate.update(
          "INSERT INTO agent_sandbox_log (run_id, session_ref, organization_id, tool, status,"
              + " script_runs, duration_ms, file_count, file_bytes, abort_reason)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          runId,
          bounded(sandbox.sessionRef(), MAX_NAME_CHARS),
          organizationId,
          bounded(sandbox.tool(), MAX_NAME_CHARS),
          bounded(sandbox.status(), MAX_CODE_CHARS),
          Math.max(0, sandbox.scriptRuns()),
          Math.max(0, sandbox.durationMs()),
          Math.max(0, sandbox.fileCount()),
          Math.max(0, sandbox.fileBytes()),
          bounded(sandbox.abortReason(), MAX_CODE_CHARS));
    } catch (DataAccessException e) {
      log.warn("Agent run {}: the sandbox log could not be written", runId, e);
    }
  }

  private static String bounded(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
