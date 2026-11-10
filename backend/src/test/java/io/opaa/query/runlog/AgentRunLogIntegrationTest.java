package io.opaa.query.runlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The step log and the sandbox log against the real schema: written without account or text, read
 * only by the system administration of the run's organization for one run with a reason, every read
 * in the audit log, the retention bounded and the sandbox log never kept longer.
 */
@OpaaIntegrationTest
class AgentRunLogIntegrationTest {

  private static final String REASON = "Störung im Agentenlauf laut Meldung";

  @Autowired private AgentRunLogWriter writer;
  @Autowired private AgentRunLogQueryService queryService;
  @Autowired private AgentRunLogRetentionService retentionService;
  @Autowired private AgentRunLogMaintenance maintenance;
  @Autowired private AuditActorPseudonymService pseudonyms;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  private final UUID runId = UUID.randomUUID();
  private UUID organizationId;
  private UUID adminId;
  private UUID auditorId;
  private CurrentUser admin;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Run Log Org")).getId();
    adminId = persistUser("admin", SystemRole.SYSTEM_ADMIN);
    auditorId = persistUser("auditor", SystemRole.AUDITOR);
    admin = CurrentUser.of(adminId, organizationId, SystemRole.SYSTEM_ADMIN, "Admin");
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM agent_step_log WHERE run_id = ?", runId);
    jdbc.update("DELETE FROM agent_sandbox_log WHERE run_id = ?", runId);
    jdbc.update("DELETE FROM users WHERE organization_id = ?", organizationId);
  }

  @Test
  void theLogsHoldTechnicalFactsOnlyAndTheAdministrationReadsOneRun() {
    writeRun(organizationId);

    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT * FROM agent_step_log WHERE run_id = ?", runId);
    assertThat(rows).hasSize(2);
    assertThat(rows.getFirst().keySet())
        .containsExactlyInAnyOrder(
            "run_id",
            "step_index",
            "recorded_at",
            "organization_id",
            "tool",
            "status",
            "hit_count",
            "duration_ms",
            "abort_reason");

    AgentRunLog log = queryService.findRun(admin, runId.toString(), REASON);

    assertThat(log.steps())
        .extracting(AgentStepLogEntry::tool, AgentStepLogEntry::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("search_knowledge", "DONE"),
            org.assertj.core.groups.Tuple.tuple("run_code", "LIMIT"));
    assertThat(log.steps()).allSatisfy(step -> assertThat(step.recordedAt()).isNotNull());
    assertThat(log.sandboxes()).singleElement().satisfies(s -> assertThat(s.fileCount()).isOne());
    assertThat(accessEntries(adminId, "SUCCESS")).isOne();
  }

  @Test
  void anyoneButTheAdministrationIsRefusedAndTheAttemptIsLogged() {
    writeRun(organizationId);
    CurrentUser auditor = CurrentUser.of(auditorId, organizationId, SystemRole.AUDITOR, "Rev");

    assertThatThrownBy(() -> queryService.findRun(auditor, runId.toString(), REASON))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> queryService.findRun(admin, runId.toString(), " "))
        .isInstanceOf(ValidationException.class);

    assertThat(accessEntries(auditorId, "DENIED")).isOne();
    assertThat(accessEntries(adminId, "DENIED")).isOne();
  }

  @Test
  void aRunOfAnotherOrganizationReadsAsUnknown() {
    writeRun(UUID.randomUUID());

    assertThatThrownBy(() -> queryService.findRun(admin, runId.toString(), REASON))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> queryService.findRun(admin, "kein-lauf", REASON))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theRetentionIsBoundedAndTheSandboxLogNeverOutlivesTheStepLog() {
    assertThat(retentionService.read(admin).getStepLogRetentionDays()).isEqualTo(7);
    assertThatThrownBy(() -> retentionService.updateRetentionDays(admin, 31, 7))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> retentionService.updateRetentionDays(admin, 5, 6))
        .isInstanceOf(ValidationException.class);

    AgentRunLogRetentionSettings changed = retentionService.updateRetentionDays(admin, 14, 3);

    assertThat(changed.getStepLogRetentionDays()).isEqualTo(14);
    assertThat(changed.getSandboxLogRetentionDays()).isEqualTo(3);
    Map<String, Object> event =
        jdbc.queryForMap(
            "SELECT before, after FROM audit_log WHERE organization_id = ? AND"
                + " event_type = 'AGENT_RUN_LOG_RETENTION_CHANGED'",
            organizationId);
    assertThat(event.get("after").toString()).contains("\"stepLogRetentionDays\":14");
  }

  @Test
  void theMaintenanceKeepsTheComingDaysAndRecordsItsRun() {
    maintenance.maintain();

    Integer days =
        jdbc.queryForObject(
            "SELECT count(*)::int FROM pg_inherits WHERE inhparent = 'agent_step_log'::regclass",
            Integer.class);
    assertThat(days).isGreaterThanOrEqualTo(31);
    assertThat(
            jdbc.queryForObject(
                "SELECT last_run_day IS NOT NULL FROM agent_run_log_retention_settings",
                Boolean.class))
        .isTrue();
  }

  private void writeRun(UUID organization) {
    writer.writeSteps(
        organization,
        runId,
        List.of(
            new AgentStepLogEntry(0, null, "search_knowledge", "DONE", 3, 12, null),
            new AgentStepLogEntry(1, null, "run_code", "LIMIT", null, 0, "LIMIT_CALLS")));
    writer.writeSandbox(
        organization,
        runId,
        new AgentSandboxLogEntry("sbx-1", null, "run_code", "COMPLETED", 2, 900, 1, 2048, null));
  }

  private long accessEntries(UUID actorId, String outcome) {
    String actorRef = pseudonyms.findExistingPseudonym(actorId).orElseThrow().toString();
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE organization_id = ? AND actor_ref = ? AND"
            + " event_type = 'AUDIT_LOG_ACCESSED' AND outcome = ? AND after::text LIKE ?",
        Long.class,
        organizationId,
        actorRef,
        outcome,
        "%" + AgentRunLogQueryService.ACCESS_PATH + "%");
  }

  private UUID persistUser(String subject, SystemRole role) {
    User user = new User(subject + "-" + UUID.randomUUID(), "test-issuer", null, subject);
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    return userRepository.save(user).getId();
  }
}
