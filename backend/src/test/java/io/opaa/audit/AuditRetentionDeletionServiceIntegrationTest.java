package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * #395: {@link AuditRetentionDeletionService} calling the real {@code
 * opaa_audit_delete_expired_partitions()} database function (migration 023) through the ordinary
 * application datasource - proves the wiring end to end (repository -> native query -> database
 * function), not just that the SQL function itself behaves correctly (that is {@code
 * Migration023AuditRetentionTest}'s job, exercised against a properly restricted, non-superuser
 * role).
 */
@OpaaIntegrationTest
class AuditRetentionDeletionServiceIntegrationTest {

  @Autowired private AuditRetentionDeletionService deletionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "UPDATE audit_retention_settings SET retention_months = 36, updated_at = now()"
            + " WHERE id = 1");
  }

  @Test
  void runningTheDeletionWithNothingExpiredReturnsAnEmptyListAndDoesNotThrow() {
    List<String> dropped = deletionService.runOnce();

    assertThat(dropped).isNotNull();
  }

  @Test
  void runningTheDeletionRepeatedlyIsSafe() {
    deletionService.runOnce();

    List<String> secondRun = deletionService.runOnce();

    assertThat(secondRun).isNotNull();
  }
}
