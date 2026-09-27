package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The retention deletion of both protocols ({@code audit_log} in the identity changeSet, {@code
 * diagnostic_context_log} in the workspace changeSet): a run deletes by the configured period
 * itself, and {@code last_cutoff} is a high-water mark that never moves back.
 *
 * <p>{@code opaa_audit_owner} is never dropped here: the baseline creates it at template-build time
 * (see {@link AbstractMigrationTest}, "Important asymmetry").
 */
class ProtocolRetentionDeletionTest extends AbstractBaselineTest {

  private static final String OWNER_ROLE = "opaa_audit_owner";

  /**
   * After a shortening from 36 to 12 months the next run removes every partition outside the new
   * period - not one calendar month of it.
   */
  @Test
  void aShorteningOfTheProtocolPeriodTakesEffectWithTheNextRunInFull() throws Exception {
    createOwnedPartition("audit_log", 30);
    createOwnedPartition("audit_log", 20);
    createOwnedPartition("audit_log", 13);
    createOwnedPartition("audit_log", 6);
    // A house that ran on 36 months and shortens to 12: the progress stands where 36 months put it.
    setRetentionState("audit_retention_settings", 12, 36, 0);

    assertThat(runDeletion("opaa_audit_delete_expired_partitions"))
        .containsExactlyInAnyOrder(
            partitionName("audit_log", 30),
            partitionName("audit_log", 20),
            partitionName("audit_log", 13));

    assertThat(partitionExists(partitionName("audit_log", 6)))
        .as("a partition inside the new period stays")
        .isTrue();
    assertThat(cutoffMonthsAgo("audit_retention_settings")).isEqualTo(12);
  }

  /** The same for the protocol of diagnoses in a foreign rights context. */
  @Test
  void aShorteningOfTheDiagnosticContextPeriodTakesEffectWithTheNextRunInFull() throws Exception {
    createOwnedPartition("diagnostic_context_log", 18);
    createOwnedPartition("diagnostic_context_log", 9);
    createOwnedPartition("diagnostic_context_log", 4);
    // Twelve months of progress reached, shortened to six: the partition nine months back goes at
    // once, not three calendar months later.
    setRetentionState("diagnostic_context_retention_settings", 6, 12, 0);

    assertThat(runDeletion("opaa_diagnostic_context_delete_expired_partitions"))
        .containsExactlyInAnyOrder(
            partitionName("diagnostic_context_log", 18),
            partitionName("diagnostic_context_log", 9));

    assertThat(partitionExists(partitionName("diagnostic_context_log", 4))).isTrue();
    assertThat(cutoffMonthsAgo("diagnostic_context_retention_settings")).isEqualTo(6);
  }

  /**
   * A lengthening protects at once and takes nothing back: the run deletes nothing, and the
   * high-water mark stays where an earlier run already got. Recording the longer period's own,
   * earlier cutoff would claim the protocol answers again for months whose partitions are gone.
   */
  @Test
  void aLengtheningDeletesNothingAndLeavesTheProgressWhereItWas() throws Exception {
    createOwnedPartition("audit_log", 30);
    setRetentionState("audit_retention_settings", 120, 36, 0);

    assertThat(runDeletion("opaa_audit_delete_expired_partitions")).isEmpty();

    assertThat(partitionExists(partitionName("audit_log", 30))).isTrue();
    assertThat(cutoffMonthsAgo("audit_retention_settings"))
        .as("last_cutoff is a high-water mark and never moves backwards")
        .isEqualTo(36);
  }

  /**
   * A lengthening, then a return to the previous period: the second run deletes by the restored
   * period at once instead of crawling back from where the lengthening had pushed the marker.
   */
  @Test
  void aReturnToTheShorterPeriodAfterALengtheningTakesEffectAtOnce() throws Exception {
    createOwnedPartition("audit_log", 40);
    createOwnedPartition("audit_log", 30);
    setRetentionState("audit_retention_settings", 120, 36, 1);

    assertThat(runDeletion("opaa_audit_delete_expired_partitions")).isEmpty();

    execute("UPDATE audit_retention_settings SET retention_months = 36 WHERE id = 1");

    assertThat(runDeletion("opaa_audit_delete_expired_partitions"))
        .containsExactly(partitionName("audit_log", 40));
    assertThat(partitionExists(partitionName("audit_log", 30))).isTrue();
  }

  /**
   * The privilege model of both functions (ADR-0015): owned by the restricted role, {@code SECURITY
   * DEFINER} - without it the {@code DROP TABLE} would run as the calling application account,
   * which owns nothing and may drop nothing - and executable by that account only. The ACL is
   * asserted rather than assumed: default rights would include EXECUTE for PUBLIC.
   */
  @Test
  void bothDeletionFunctionsAreOwnedByTheRestrictedRoleAndExecutableByTheCallerOnly()
      throws Exception {

    for (String function :
        List.of(
            "opaa_audit_delete_expired_partitions",
            "opaa_diagnostic_context_delete_expired_partitions")) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT prosecdef, proowner::regrole::text AS owner, proacl IS NOT NULL AS has_acl,"
                  + " EXISTS (SELECT 1 FROM unnest(proacl) entry WHERE entry::text LIKE '=%')"
                  + "   AS execute_for_public,"
                  + " EXISTS (SELECT 1 FROM unnest(proacl) entry"
                  + "   WHERE entry::text LIKE current_user || '=X%') AS execute_for_caller"
                  + " FROM pg_catalog.pg_proc"
                  + " WHERE proname = ? AND pronamespace = current_schema()::regnamespace")) {
        statement.setString(1, function);
        try (ResultSet rows = statement.executeQuery()) {
          assertThat(rows.next()).as(function + " exists").isTrue();
          assertThat(rows.getBoolean("prosecdef")).as(function + " is SECURITY DEFINER").isTrue();
          assertThat(rows.getString("owner")).isEqualTo(OWNER_ROLE);
          // A NULL proacl means "default rights", which include EXECUTE for PUBLIC - and would let
          // both assertions below pass while saying nothing.
          assertThat(rows.getBoolean("has_acl"))
              .as(function + " carries an explicit ACL, not the default one")
              .isTrue();
          assertThat(rows.getBoolean("execute_for_public"))
              .as(function + " is not executable by PUBLIC")
              .isFalse();
          assertThat(rows.getBoolean("execute_for_caller"))
              .as(function + " stays executable by the account the baseline granted it to")
              .isTrue();
        }
      }
    }
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  private void setRetentionState(
      String settingsTable, int retentionMonths, int cutoffMonthsAgo, int lastRunMonthsAgo)
      throws SQLException {
    execute(
        "UPDATE "
            + settingsTable
            + " SET retention_months = "
            + retentionMonths
            + ", last_cutoff = date_trunc('month', now()) - interval '"
            + cutoffMonthsAgo
            + " months', last_run_month = (date_trunc('month', now()) - interval '"
            + lastRunMonthsAgo
            + " months')::date WHERE id = 1");
  }

  private List<String> runDeletion(String function) throws SQLException {
    List<String> dropped = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT * FROM " + function + "()")) {
      while (rows.next()) {
        dropped.add(rows.getString(1));
      }
    }
    return dropped;
  }

  private int cutoffMonthsAgo(String settingsTable) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT ((extract(year FROM date_trunc('month', now())) - extract(year FROM"
                    + " last_cutoff)) * 12 + (extract(month FROM date_trunc('month', now())) -"
                    + " extract(month FROM last_cutoff)))::int AS months FROM "
                    + settingsTable
                    + " WHERE id = 1")) {
      assertThat(rows.next()).isTrue();
      return rows.getInt("months");
    }
  }

  /**
   * The function drops partitions as {@code opaa_audit_owner}, which requires ownership - the
   * baseline hands every partition it creates to that role for the same reason.
   */
  private void createOwnedPartition(String parentTable, int monthsAgo) throws SQLException {
    String name = partitionName(parentTable, monthsAgo);
    execute(
        "CREATE TABLE "
            + name
            + " PARTITION OF "
            + parentTable
            + " FOR VALUES FROM ((date_trunc('month', now()) - interval '"
            + monthsAgo
            + " months')::date) TO ((date_trunc('month', now()) - interval '"
            + (monthsAgo - 1)
            + " months')::date)");
    execute("ALTER TABLE " + name + " OWNER TO " + OWNER_ROLE);
  }

  private String partitionName(String parentTable, int monthsAgo) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT to_char(date_trunc('month', now()) - (? || ' months')::interval, 'YYYY_MM')")) {
      statement.setInt(1, monthsAgo);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return parentTable + "_" + rows.getString(1);
      }
    }
  }

  private boolean partitionExists(String partitionName) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM pg_catalog.pg_class WHERE relname = ? AND relnamespace ="
                + " current_schema()::regnamespace")) {
      statement.setString(1, partitionName);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getLong(1) > 0;
      }
    }
  }
}
