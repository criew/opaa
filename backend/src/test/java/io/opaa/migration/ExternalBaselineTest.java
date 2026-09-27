package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the baseline's external changeSet (ADR-0035): the channel settings of external
 * access and the personal access tokens with their library selection.
 */
class ExternalBaselineTest extends AbstractBaselineTest {

  /** A fresh installation is closed: the same flag is the emergency stop. */
  @Test
  void theChannelSettingsAreASingletonSeededClosedWithTheDeliveredDefaults() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT id, enabled, token_max_lifetime_days, token_rate_limit_per_hour,"
                    + " allowed_cidrs, mass_retrieval_alert_threshold, server_instructions,"
                    + " updated_by, version FROM external_access_settings")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt("id")).isEqualTo(1);
      assertThat(rows.getBoolean("enabled")).isFalse();
      assertThat(rows.getInt("token_max_lifetime_days")).isEqualTo(90);
      assertThat(rows.getInt("token_rate_limit_per_hour")).isEqualTo(60);
      assertThat(rows.getString("allowed_cidrs"))
          .isEqualTo("10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.0/8,::1/128,fc00::/7");
      assertThat(rows.getInt("mass_retrieval_alert_threshold")).isEqualTo(600);
      assertThat(rows.getString("server_instructions")).startsWith("Bei Fragen zu");
      assertThat(rows.getObject("updated_by")).isNull();
      assertThat(rows.getLong("version")).isZero();
      assertThat(rows.next()).as("exactly one row").isFalse();
    }
    assertRejected(
        "INSERT INTO external_access_settings (id) VALUES (2)",
        "chk_external_access_settings_singleton");
  }

  /** The bounds of every value are CHECKs as well as service validation. */
  @Test
  void theChannelLimitsStayPositiveAndBounded() throws SQLException {
    assertBounds("token_max_lifetime_days", 1, 365, "chk_external_access_settings_token_lifetime");
    assertBounds("token_rate_limit_per_hour", 1, 10000, "chk_external_access_settings_rate_limit");
    assertBounds(
        "mass_retrieval_alert_threshold",
        1,
        1000000,
        "chk_external_access_settings_alert_threshold");
  }

  @Test
  void theChannelSettingsOutliveTheirLastEditor() throws SQLException {
    UUID admin = insertUser();
    execute("UPDATE external_access_settings SET updated_by = '" + admin + "'");

    execute("DELETE FROM users WHERE id = '" + admin + "'");

    assertThat(countWhere("external_access_settings", "updated_by IS NULL")).isEqualTo(1);
  }

  /** Only the lookup value of a token is stored, once; a revocation is complete and named. */
  @Test
  void aTokenHasAUniqueLookupValueANameAndACompleteKnownRevocation() throws SQLException {
    UUID user = insertUser();
    UUID token = insertToken(user, "a".repeat(64), "Arbeitsplatz");
    String where = " WHERE id = '" + token + "'";

    assertRejected(
        tokenSql(UUID.randomUUID(), user, "a".repeat(64), "Zweit"),
        "ux_external_access_tokens_hash");
    assertRejected(
        tokenSql(UUID.randomUUID(), user, "b".repeat(64), "   "),
        "chk_external_access_tokens_name");
    assertRejected(
        "UPDATE external_access_tokens SET revoked_at = now()" + where,
        "chk_external_access_tokens_revocation");
    assertRejected(
        "UPDATE external_access_tokens SET revoked_at = now(), revocation_reason = 'BOREDOM'"
            + where,
        "chk_external_access_tokens_revocation_reason");
    execute(
        "UPDATE external_access_tokens SET revoked_at = now(), revocation_reason = 'OWNER'"
            + where);
  }

  /**
   * A library appears once per selection and leaves it when deleted; the person takes their tokens
   * and selections with them.
   */
  @Test
  void aSelectionNamesALibraryOnceAndTokensAndSelectionsGoWithTheirPersonAndLibrary()
      throws SQLException {
    UUID user = insertUser();
    UUID token = insertToken(user, "c".repeat(64), "Arbeitsplatz");
    UUID kept = insertLibrary();
    UUID dropped = insertLibrary();
    execute(selectionSql(token, kept));
    execute(selectionSql(token, dropped));

    assertRejected(selectionSql(token, kept), "external_access_token_libraries_pkey");
    execute("DELETE FROM assets WHERE id = '" + dropped + "'");
    assertThat(countRows("external_access_token_libraries")).isEqualTo(1);

    execute("DELETE FROM users WHERE id = '" + user + "'");
    assertThat(countRows("external_access_tokens")).isZero();
    assertThat(countRows("external_access_token_libraries")).isZero();
  }

  private void assertBounds(String column, int lowest, int highest, String constraint)
      throws SQLException {
    assertRejected(
        "UPDATE external_access_settings SET " + column + " = " + (lowest - 1), constraint);
    assertRejected(
        "UPDATE external_access_settings SET " + column + " = " + (highest + 1), constraint);
    execute("UPDATE external_access_settings SET " + column + " = " + highest);
  }

  private UUID insertToken(UUID user, String lookupHash, String name) throws SQLException {
    UUID id = UUID.randomUUID();
    execute(tokenSql(id, user, lookupHash, name));
    return id;
  }

  private static String tokenSql(UUID id, UUID user, String lookupHash, String name) {
    return "INSERT INTO external_access_tokens (id, user_id, name, token_prefix,"
        + " token_lookup_hash, expires_at) VALUES ('"
        + id
        + "', '"
        + user
        + "', '"
        + name
        + "', 'opaa_ext_ab', '"
        + lookupHash
        + "', now() + interval '90 days')";
  }

  private static String selectionSql(UUID token, UUID library) {
    return "INSERT INTO external_access_token_libraries (token_id, library_id) VALUES ('"
        + token
        + "', '"
        + library
        + "')";
  }
}
