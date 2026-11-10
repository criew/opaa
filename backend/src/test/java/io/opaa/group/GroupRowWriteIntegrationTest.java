package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.GroupKind;
import io.opaa.organization.Organization;
import io.opaa.test.ForeignCommit;
import io.opaa.test.OpaaIntegrationTest;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A group read in a transaction and written back after a change of its name keeps a deactivation
 * another session committed after the read. The interleaving is fixed: the deactivation commits
 * between the read and the write.
 */
@OpaaIntegrationTest
class GroupRowWriteIntegrationTest {

  @Autowired private GroupRepository groups;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private UUID groupId;

  @AfterEach
  void removeOwnGroup() {
    if (groupId != null) {
      jdbc.update("DELETE FROM groups WHERE id = ?", groupId);
    }
  }

  @Test
  void aRenameDoesNotUndoADeactivationCommittedAfterItsRead() {
    Group created =
        new Group(
            Organization.DEFAULT_ID,
            GroupKind.AD_HOC,
            "Zeilenprobe " + UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null);
    groupId = groups.saveAndFlush(created).getId();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              Group read = groups.findById(groupId).orElseThrow();
              ForeignCommit.commit(
                  dataSource,
                  "UPDATE groups SET deactivated = true, deactivated_at = now() WHERE id = ?",
                  groupId);
              read.updateDetails("Zeilenprobe umbenannt " + groupId, "neu");
              groups.save(read);
            });

    Map<String, Object> row =
        jdbc.queryForMap("SELECT deactivated, description FROM groups WHERE id = ?", groupId);
    assertThat(row.get("deactivated")).isEqualTo(true);
    assertThat(row.get("description")).isEqualTo("neu");
  }
}
