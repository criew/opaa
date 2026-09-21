package io.opaa.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.organization.Organization;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link SeededRowRestorer} restores what it found, and nothing a test wrote itself. The two
 * capability tables are the only mixed ones, and their delivered rows are shaped exactly like a
 * grant a test may write: a predicate over the row shape would delete both. The two methods below
 * run in order and span the restore that happens between them - the first writes a row of its own,
 * the second finds it still there next to the delivered rows.
 *
 * <p>{@code @TestInstance(PER_CLASS)} so the id survives the two methods without a static field;
 * neither annotation enters the context cache key, so this class shares the canonical context.
 */
@OpaaIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeededRowRestorerIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID ownGrantId;

  @AfterAll
  void removeOwnRow() {
    if (ownGrantId != null) {
      jdbcTemplate.update("DELETE FROM capability_grants WHERE id = ?", ownGrantId);
      ownGrantId = null;
    }
  }

  /**
   * {@code CREATE_INTERNAL_GROUP} on purpose: it is the one capability without a delivered row, so
   * this grant cannot collide with one and is unmistakably this class's own.
   */
  @Test
  @Order(1)
  void writesAGrantToAllAccountsOfItsOwn() {
    ownGrantId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO capability_grants (id, organization_id, capability, subject_type)"
            + " VALUES (?, ?, 'CREATE_INTERNAL_GROUP', 'ALL_ACCOUNTS')",
        ownGrantId,
        Organization.DEFAULT_ID);

    assertThat(exists(ownGrantId)).isTrue();
  }

  @Test
  @Order(2)
  void findsItStillThereAfterTheRestoreAndTheDeliveredRowsUntouched() {
    assertThat(exists(ownGrantId))
        .as("the restorer owns the rows it found, not the ones this class wrote")
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM capability_grants WHERE organization_id = ?"
                    + " AND subject_type = 'ALL_ACCOUNTS'"
                    + " AND capability <> 'CREATE_INTERNAL_GROUP'",
                Integer.class,
                Organization.DEFAULT_ID))
        .as("and the three delivered rows are where they were")
        .isEqualTo(3);
  }

  private boolean exists(UUID grantId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "SELECT exists(SELECT 1 FROM capability_grants WHERE id = ?)", Boolean.class, grantId));
  }
}
