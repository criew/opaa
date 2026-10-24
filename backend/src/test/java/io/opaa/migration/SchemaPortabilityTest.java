package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.config.DatabaseSchemaGuard;
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
 * Every migration applied into a schema other than {@code public}, configured the way the
 * application configures it (ADR-0034): the connection's {@code search_path} starts with the target
 * schema, followed by {@code public}, and Flyway's default schema names it. Every object of OPAA
 * must land in the target schema, and the SECURITY DEFINER functions must pin exactly that schema
 * in their own {@code search_path}. The session runs fourteen hours off UTC, so a seed that
 * followed the session zone instead of UTC shows as well.
 */
class SchemaPortabilityTest extends AbstractMigrationTest {

  private static final String SCHEMA = "opaa_portable";
  private static final String TIME_ZONE = "Pacific/Kiritimati";

  private Connection connection;

  @Override
  protected List<String> baseFixtureMigrations() {
    return List.of();
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + SCHEMA);
      statement.execute("SET search_path TO " + SCHEMA + ", public");
      statement.execute("SET TIME ZONE '" + TIME_ZONE + "'");
    }
    // Flyway migrates over connections of its own, which take search_path and zone from the URL.
    String url = currentDatabaseUrl();
    Migrations.apply(
        url
            + (url.contains("?") ? "&" : "?")
            + "currentSchema="
            + SCHEMA
            + ",public&options=-c%20TimeZone%3D"
            + TIME_ZONE,
        POSTGRES.getUsername(),
        POSTGRES.getPassword(),
        SCHEMA,
        Migrations.all());
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void createsNoRelationAndNoFunctionInPublic() throws SQLException {
    assertThat(
            strings(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = 'public'"))
        .isEmpty();
    assertThat(
            strings(
                "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                    + " WHERE n.nspname = 'public'"))
        .isEmpty();
  }

  @Test
  void placesTablesAndFlywayBookkeepingInTheTargetSchema() throws SQLException {
    assertThat(
            strings(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                    + SCHEMA
                    + "'"))
        .contains(
            "flyway_schema_history",
            "users",
            "knowledge_libraries",
            "audit_log",
            "diagnostic_context_log",
            "chat_note_items",
            "chat_personal_marks");
  }

  @Test
  void pinsTheTargetSchemaInEveryFunctionsSearchPath() throws SQLException {
    List<String> configs =
        strings(
            "SELECT p.proname || ' ' || array_to_string(p.proconfig, ',') FROM pg_proc p"
                + " JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = '"
                + SCHEMA
                + "' AND p.proconfig IS NOT NULL");
    assertThat(configs)
        .containsExactlyInAnyOrder(
            "asset_grants_guard_owner_only search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "assets_guard_owner_only search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "permission_history_next_boundary search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "account_state_issuer_key search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "account_state_reconcile search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "account_state_on_credentials_change search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "account_state_on_user_change search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "account_state_on_provider_change search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "account_state_record_reached_expiries search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "audit_incident_scope_grants_pseudonymize_person search_path=pg_catalog, "
                + SCHEMA
                + ", pg_temp",
            "library_connections_guard_owner_only search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "connection_profiles_guard_owner_only search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "knowledge_libraries_guard_owner_only_release search_path=pg_catalog, "
                + SCHEMA
                + ", pg_temp",
            "chat_library_references_set_organization search_path=pg_catalog, "
                + SCHEMA
                + ", pg_temp",
            "chat_note_items_set_organization search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "chat_personal_marks_set_organization search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "opaa_audit_delete_expired_partitions search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "opaa_connection_log_delete_expired_partitions search_path=pg_catalog, "
                + SCHEMA
                + ", pg_temp",
            "opaa_diagnostic_context_delete_expired_partitions search_path=pg_catalog, "
                + SCHEMA
                + ", pg_temp",
            "organizations_seed_capability_grants search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "notify_cache_invalidation search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "local_credentials_session_generation search_path=pg_catalog, " + SCHEMA + ", pg_temp");
  }

  /** Reads the unqualified retention settings table through the pinned search_path. */
  @Test
  void retentionFunctionsRunAgainstTheTargetSchema() throws SQLException {
    strings("SELECT * FROM opaa_audit_delete_expired_partitions()");
    strings("SELECT * FROM opaa_diagnostic_context_delete_expired_partitions()");
    strings("SELECT * FROM opaa_connection_log_delete_expired_partitions()");

    assertThat(strings("SELECT last_run_month::text FROM audit_retention_settings")).hasSize(1);
  }

  /**
   * The rights history's deletion pass computes its target month in UTC, so the seeded progress
   * must be midnight on the first of a month read in UTC - otherwise it sits hours beside that
   * target for ever and every pass reports a period that is "not fully effective yet".
   */
  @Test
  void seedsThePermissionHistoryRetentionProgressOnAUtcMonthBoundary() throws SQLException {
    assertThat(
            strings(
                "SELECT ((last_cutoff AT TIME ZONE 'UTC')::time = time '00:00:00'"
                    + " AND date_part('day', last_cutoff AT TIME ZONE 'UTC') = 1)::text"
                    + " FROM permission_history_retention_settings WHERE id = 1"))
        .containsExactly("true");
  }

  @Test
  void createsTheVectorStoreWithItsIndexesInTheTargetSchema() throws Exception {
    assertThat(
            strings(
                "SELECT indexname FROM pg_indexes WHERE schemaname = '"
                    + SCHEMA
                    + "' AND tablename = 'vector_store'"))
        .containsExactlyInAnyOrder(
            "vector_store_pkey",
            "spring_ai_vector_index",
            "idx_vector_store_library_id",
            "idx_vector_store_document_id");
  }

  @Test
  void guardRejectsExactlyTheKeywordsPostgresRefusesAsSchemaName() throws SQLException {
    assertThat(strings("SELECT word FROM pg_get_keywords() WHERE catcode IN ('R', 'T')"))
        .containsExactlyInAnyOrderElementsOf(DatabaseSchemaGuard.NON_SCHEMA_KEYWORDS);
  }

  private List<String> strings(String sql) throws SQLException {
    List<String> result = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet rs = statement.executeQuery()) {
      while (rs.next()) {
        result.add(rs.getString(1));
      }
    }
    return result;
  }
}
