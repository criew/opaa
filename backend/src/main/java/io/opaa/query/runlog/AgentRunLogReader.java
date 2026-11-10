package io.opaa.query.runlog;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The only reader of the step log and the sandbox log: one run of one organization, nothing else.
 * There is deliberately no query by time, tool, organization alone or anything that leads to a
 * person ({@code AgentRunLogStructureTest}).
 */
@Component
class AgentRunLogReader {

  private final JdbcTemplate jdbcTemplate;

  AgentRunLogReader(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  List<AgentStepLogEntry> steps(UUID organizationId, UUID runId) {
    return jdbcTemplate.query(
        "SELECT step_index, recorded_at, tool, status, hit_count, duration_ms, abort_reason"
            + " FROM agent_step_log WHERE organization_id = ? AND run_id = ?"
            + " ORDER BY step_index, recorded_at",
        (rs, row) ->
            new AgentStepLogEntry(
                rs.getInt("step_index"),
                instant(rs.getTimestamp("recorded_at")),
                rs.getString("tool"),
                rs.getString("status"),
                rs.getObject("hit_count", Integer.class),
                rs.getLong("duration_ms"),
                rs.getString("abort_reason")),
        organizationId,
        runId);
  }

  List<AgentSandboxLogEntry> sandboxes(UUID organizationId, UUID runId) {
    return jdbcTemplate.query(
        "SELECT session_ref, recorded_at, tool, status, script_runs, duration_ms, file_count,"
            + " file_bytes, abort_reason FROM agent_sandbox_log"
            + " WHERE organization_id = ? AND run_id = ? ORDER BY recorded_at",
        (rs, row) ->
            new AgentSandboxLogEntry(
                rs.getString("session_ref"),
                instant(rs.getTimestamp("recorded_at")),
                rs.getString("tool"),
                rs.getString("status"),
                rs.getInt("script_runs"),
                rs.getLong("duration_ms"),
                rs.getInt("file_count"),
                rs.getLong("file_bytes"),
                rs.getString("abort_reason")),
        organizationId,
        runId);
  }

  private static java.time.Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
