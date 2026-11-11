package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.organization.Organization;
import io.opaa.test.ForeignCommit;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A transfer raises the target group's grant from the row it read; an administrator's downgrade of
 * that grant committed after the read is not undone. The transfer reads the row as {@link
 * PermissionTransferService} does, through {@link AssetGrantRepository#findGroupGrantForUpdate}, so
 * the downgrade waits for the transfer's commit and lands after it.
 */
@OpaaIntegrationTest
class GroupGrantTransferRowWriteIntegrationTest {

  private static final AssetType LIBRARY = AssetType.of("KNOWLEDGE_LIBRARY");

  @Autowired private AssetGrantRepository grants;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private final UUID userId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID libraryId = UUID.randomUUID();
  private final UUID grantId = UUID.randomUUID();

  @AfterEach
  void removeOwnRows() {
    jdbc.update("DELETE FROM asset_grants WHERE asset_id = ?", libraryId);
    jdbc.update("DELETE FROM knowledge_libraries WHERE id = ?", libraryId);
    jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", libraryId);
    jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", libraryId);
    jdbc.update("DELETE FROM assets WHERE id = ?", libraryId);
    jdbc.update("DELETE FROM groups WHERE id = ?", groupId);
    jdbc.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void aTransferDoesNotUndoADowngradeCommittedAfterItsRead() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant targetExpiry = now.plus(Duration.ofDays(20));
    Instant sourceExpiry = now.plus(Duration.ofDays(200));
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Übertragungsprobe', now(),"
            + " 'USER', ?)",
        userId,
        "uebertragung-" + userId,
        "uebertragung-" + userId + "@example.com",
        Organization.DEFAULT_ID);
    jdbc.update(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        groupId,
        Organization.DEFAULT_ID,
        "Übertragungsprobe " + groupId);
    jdbc.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Übertragungsprobe', 'USER', ?)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        libraryId,
        Organization.DEFAULT_ID,
        userId);
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_id, organization_id, subject_type, subject_group_id,"
            + " role, expires_at, granted_by_user_id, asset_type)"
            + " VALUES (?, ?, ?, 'GROUP', ?, 'EDITOR', ?, ?, 'KNOWLEDGE_LIBRARY')",
        grantId,
        libraryId,
        Organization.DEFAULT_ID,
        groupId,
        Timestamp.from(targetExpiry),
        userId);

    AtomicReference<ForeignCommit> started = new AtomicReference<>();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              AssetGrant target =
                  grants.findGroupGrantForUpdate(LIBRARY, libraryId, groupId).orElseThrow();
              ForeignCommit downgrade =
                  ForeignCommit.start(
                      dataSource,
                      "UPDATE asset_grants SET role = 'VIEWER', expires_at = ? WHERE id = ?",
                      Timestamp.from(targetExpiry),
                      grantId);
              downgrade.isWaiting(Duration.ofMillis(500));
              target.updateRole(AssetRole.EDITOR, sourceExpiry, userId, now);
              grants.save(target);
              status.flush();
              started.set(downgrade);
            });
    started.get().awaitCommitted();

    Map<String, Object> row =
        jdbc.queryForMap("SELECT role, expires_at FROM asset_grants WHERE id = ?", grantId);
    assertThat(row.get("role")).isEqualTo("VIEWER");
    assertThat(((Timestamp) row.get("expires_at")).toInstant()).isEqualTo(targetExpiry);
  }
}
