package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The delta migration of the checksum chain (ADR-0058, Entscheidung 4) on an installation that has
 * everything else: the two chain tables belong to {@code opaa_audit_owner} and leave the
 * application account INSERT and SELECT only (ADR-0015), and the retention function takes the
 * checkpoints and the seal of a partition it drops with it, and only those.
 */
class AuditChainMigrationTest extends AbstractMigrationTest {

  private static final String SCRIPT = "db/migration/identity/V2026.10.25.2__audit_chain.sql";
  private static final String OWNER_ROLE = "opaa_audit_owner";

  private Connection connection;

  @Override
  protected List<String> baseFixtureMigrations() {
    return Migrations.except(SCRIPT);
  }

  @BeforeEach
  void applyTheMigration() throws SQLException {
    connection = connect();
    connection.setAutoCommit(true);
    applyMigrations(connection, SCRIPT);
  }

  @AfterEach
  void closeConnection() throws SQLException {
    connection.close();
  }

  @Test
  void theChainTablesBelongToTheOwnerRoleAndGrantTheApplicationInsertAndSelectOnly()
      throws SQLException {
    for (String table : List.of("audit_chain_checkpoints", "audit_chain_seals")) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT relowner::regrole::text AS owner, relacl::text AS acl FROM pg_catalog.pg_class"
                  + " WHERE relname = ? AND relnamespace = current_schema()::regnamespace")) {
        statement.setString(1, table);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).as(table + " exists").isTrue();
          assertThat(rows.getString("owner")).isEqualTo(OWNER_ROLE);
          // "a" INSERT, "r" SELECT and nothing else for the migrating account; no entry for PUBLIC
          String user = connection.getMetaData().getUserName();
          assertThat(rows.getString("acl"))
              .contains(user + "=ar/" + OWNER_ROLE)
              .doesNotContain(user + "=arwdDxtm/")
              .doesNotContain("{=")
              .doesNotContain(",=");
        }
      }
    }
  }

  @Test
  void droppingAnExpiredPartitionTakesItsCheckpointsAndSealWithItAndLeavesTheOthers()
      throws SQLException {
    createOwnedPartition(40);
    createOwnedPartition(30);
    insertChain(40);
    insertChain(30);
    execute(
        "UPDATE audit_retention_settings SET retention_months = 36,"
            + " last_cutoff = date_trunc('month', now()) - interval '36 months' WHERE id = 1");

    List<String> dropped = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery("SELECT * FROM opaa_audit_delete_expired_partitions()")) {
      while (rows.next()) {
        dropped.add(rows.getString(1));
      }
    }

    assertThat(dropped).containsExactly(partitionName(40));
    assertThat(count("audit_chain_checkpoints", 40)).isZero();
    assertThat(count("audit_chain_seals", 40)).isZero();
    assertThat(count("audit_chain_checkpoints", 30)).isEqualTo(2);
    assertThat(count("audit_chain_seals", 30)).isEqualTo(1);
  }

  @Test
  void aCheckpointAtPositionZeroCarriesNoKeyAndALaterOneAlways() {
    assertThatRejected(
        "INSERT INTO audit_chain_checkpoints (month, position, last_recorded_at, last_event_id,"
            + " head_hash, algorithm, created_at) VALUES (date_trunc('month', now())::date, 1,"
            + " NULL, NULL, decode(repeat('ab', 32), 'hex'), 'OPAA-AUDIT-CHAIN-1', now())",
        "chk_audit_chain_checkpoints_last_key");
    assertThatRejected(
        "INSERT INTO audit_chain_seals (month, entry_count, head_hash, algorithm, sealed_at,"
            + " seal_event_id) VALUES ((date_trunc('month', now()) + interval '3 days')::date, 0,"
            + " decode(repeat('ab', 32), 'hex'), 'OPAA-AUDIT-CHAIN-1', now(), gen_random_uuid())",
        "chk_audit_chain_seals_month");
  }

  private void assertThatRejected(String sql, String constraint) {
    assertThatThrownBy(() -> execute(sql))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(constraint);
  }

  private void insertChain(int monthsAgo) throws SQLException {
    String month = "(date_trunc('month', now()) - interval '" + monthsAgo + " months')::date";
    execute(
        "INSERT INTO audit_chain_checkpoints (month, position, last_recorded_at, last_event_id,"
            + " head_hash, algorithm, created_at) VALUES ("
            + month
            + ", 2, now(), gen_random_uuid(), decode(repeat('ab', 32), 'hex'),"
            + " 'OPAA-AUDIT-CHAIN-1', now()), ("
            + month
            + ", 4, now(), gen_random_uuid(), decode(repeat('cd', 32), 'hex'),"
            + " 'OPAA-AUDIT-CHAIN-1', now())");
    execute(
        "INSERT INTO audit_chain_seals (month, entry_count, head_hash, algorithm, sealed_at,"
            + " seal_event_id) VALUES ("
            + month
            + ", 4, decode(repeat('cd', 32), 'hex'), 'OPAA-AUDIT-CHAIN-1', now(),"
            + " gen_random_uuid())");
  }

  private long count(String table, int monthsAgo) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT count(*) FROM "
                    + table
                    + " WHERE month = (date_trunc('month', now()) - interval '"
                    + monthsAgo
                    + " months')::date")) {
      assertThat(rows.next()).isTrue();
      return rows.getLong(1);
    }
  }

  private void createOwnedPartition(int monthsAgo) throws SQLException {
    String name = partitionName(monthsAgo);
    execute(
        "CREATE TABLE "
            + name
            + " PARTITION OF audit_log FOR VALUES FROM ((date_trunc('month', now()) - interval '"
            + monthsAgo
            + " months')::date) TO ((date_trunc('month', now()) - interval '"
            + (monthsAgo - 1)
            + " months')::date)");
    execute("ALTER TABLE " + name + " OWNER TO " + OWNER_ROLE);
  }

  private String partitionName(int monthsAgo) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT to_char(date_trunc('month', now()) - (? || ' months')::interval, 'YYYY_MM')")) {
      statement.setInt(1, monthsAgo);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return "audit_log_" + rows.getString(1);
      }
    }
  }

  private void execute(String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}
