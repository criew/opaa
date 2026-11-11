package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.OwnAddressStock;
import io.opaa.knowledge.SourceType;
import io.opaa.test.ForeignCommit;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Switching the profile requirement of a source type keeps a lock of the type another session
 * committed after the read. The interleaving is fixed: the lock commits between the read and the
 * write. A lock that raises the version, as every versioned write does, makes a saved detached
 * instance fail instead of unlocking the type again.
 */
@OpaaIntegrationTest
class ConnectorTypePolicyRowWriteIntegrationTest {

  @Autowired private ConnectorTypePolicyRepository policies;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  private final String key =
      "ROW" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

  @AfterEach
  void removeOwnPolicy() {
    jdbc.update("DELETE FROM connector_type_policies WHERE source_type = ?", key);
  }

  @Test
  void aProfileRequirementDoesNotUndoATypeLockCommittedAfterItsRead() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    policies.saveAndFlush(new ConnectorTypePolicy(SourceType.of(key), now));

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              ConnectorTypePolicy read = policies.findById(key).orElseThrow();
              ForeignCommit.commit(
                  dataSource,
                  "UPDATE connector_type_policies SET locked_at = now() WHERE source_type = ?",
                  key);
              read.requireProfiles(OwnAddressStock.RUNS, now);
              policies.save(read);
            });

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT locked_at, own_address_stock FROM connector_type_policies"
                + " WHERE source_type = ?",
            key);
    assertThat(row.get("locked_at")).isNotNull();
    assertThat(row.get("own_address_stock")).isEqualTo("RUNS");
  }

  @Test
  void aDetachedPolicySavedAfterATypeLockFailsInsteadOfUnlockingTheType() {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    policies.saveAndFlush(new ConnectorTypePolicy(SourceType.of(key), now));
    ConnectorTypePolicy detached = policies.findById(key).orElseThrow();
    ForeignCommit.commit(
        dataSource,
        "UPDATE connector_type_policies SET locked_at = now(), version = version + 1"
            + " WHERE source_type = ?",
        key);

    detached.requireProfiles(OwnAddressStock.RUNS, now);
    assertThatThrownBy(() -> policies.save(detached))
        .isInstanceOf(OptimisticLockingFailureException.class);

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT locked_at, own_address_stock FROM connector_type_policies"
                + " WHERE source_type = ?",
            key);
    assertThat(row.get("locked_at")).isNotNull();
    assertThat(row.get("own_address_stock")).isNull();
  }
}
