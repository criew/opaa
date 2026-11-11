package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.GroupKind;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.test.ForeignCommit;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A group read in a transaction and written back after a change of its name keeps a deactivation
 * another session committed after the read. The interleaving is fixed: the deactivation commits
 * between the read and the write. A deactivation that raises the version, as every versioned write
 * does, makes a saved detached instance fail instead of writing back the group it read; members
 * added at the same time never make each other fail.
 */
@OpaaIntegrationTest
class GroupRowWriteIntegrationTest {

  @Autowired private GroupRepository groups;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;

  private UUID groupId;
  private final List<UUID> userIds = new ArrayList<>();

  @AfterEach
  void removeOwnGroup() {
    if (groupId != null) {
      jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", groupId);
      jdbc.update("DELETE FROM groups WHERE id = ?", groupId);
    }
    userIds.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
  }

  @Test
  void aDetachedGroupSavedAfterADeactivationFailsInsteadOfLiftingIt() {
    groupId = groups.saveAndFlush(internalGroup()).getId();
    Group detached = groups.findById(groupId).orElseThrow();
    ForeignCommit.commit(
        dataSource,
        "UPDATE groups SET deactivated = true, deactivated_at = now(), version = version + 1"
            + " WHERE id = ?",
        groupId);

    detached.updateDetails(detached.getName(), "neu");
    assertThatThrownBy(() -> groups.save(detached))
        .isInstanceOf(OptimisticLockingFailureException.class);

    Map<String, Object> row =
        jdbc.queryForMap("SELECT deactivated, description FROM groups WHERE id = ?", groupId);
    assertThat(row.get("deactivated")).isEqualTo(true);
    assertThat(row.get("description")).isNull();
  }

  @Test
  void membersAddedAtTheSameTimeDoNotMakeEachOtherFail() {
    groupId = groups.saveAndFlush(internalGroup()).getId();
    UUID first = user();
    UUID second = user();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    transaction.executeWithoutResult(
        status -> {
          Group read = groups.findById(groupId).orElseThrow();
          CompletableFuture.runAsync(() -> transaction.executeWithoutResult(other -> add(second)))
              .join();
          read.addMembership(new GroupMembership(first, Organization.DEFAULT_ID));
          groups.saveAndFlush(read);
        });

    assertThat(
            jdbc.queryForList(
                "SELECT user_id FROM group_memberships WHERE group_id = ?", UUID.class, groupId))
        .containsExactlyInAnyOrder(first, second);
  }

  private void add(UUID userId) {
    Group group = groups.findById(groupId).orElseThrow();
    group.addMembership(new GroupMembership(userId, Organization.DEFAULT_ID));
    groups.saveAndFlush(group);
  }

  private UUID user() {
    User user =
        new User(UUID.randomUUID().toString(), "row-write-group", "mitglied@stadt.example", "M");
    user.setOrganizationId(Organization.DEFAULT_ID);
    UUID id = users.saveAndFlush(user).getId();
    userIds.add(id);
    return id;
  }

  private static Group internalGroup() {
    return new Group(
        Organization.DEFAULT_ID,
        GroupKind.AD_HOC,
        "Zeilenprobe " + UUID.randomUUID(),
        null,
        null,
        null,
        null,
        null);
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
