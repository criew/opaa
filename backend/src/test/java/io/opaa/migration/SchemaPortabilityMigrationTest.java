package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole master changelog applied into a schema other than {@code public}, configured the way
 * the application configures it (ADR-0034): the connection's {@code search_path} starts with the
 * target schema, followed by {@code public}, and Liquibase's default schema names it. Every object
 * of OPAA must land in the target schema, and the SECURITY DEFINER functions must pin exactly that
 * schema in their own {@code search_path}.
 */
class SchemaPortabilityMigrationTest extends AbstractMigrationTest {

  private static final String SCHEMA = "opaa_portable";
  private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-empty.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    try (Statement statement = connection.createStatement()) {
      // Left behind in public by the empty template fixture, which runs without a target schema.
      statement.execute("DROP TABLE public.databasechangelog, public.databasechangeloglock");
      statement.execute("CREATE SCHEMA " + SCHEMA);
      statement.execute("SET search_path TO " + SCHEMA + ", public");
    }
    applyMasterChangelog();
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
  void placesTablesAndLiquibaseBookkeepingInTheTargetSchema() throws SQLException {
    assertThat(
            strings(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                    + SCHEMA
                    + "'"))
        .contains(
            "databasechangelog",
            "users",
            "knowledge_libraries",
            "audit_log",
            "diagnostic_context_log",
            "chat_note_items");
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
            "chat_library_references_set_organization search_path=pg_catalog, "
                + SCHEMA
                + ", pg_temp",
            "chat_note_items_set_organization search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "opaa_audit_delete_expired_partitions search_path=pg_catalog, " + SCHEMA + ", pg_temp",
            "opaa_diagnostic_context_delete_expired_partitions search_path=pg_catalog, "
                + SCHEMA
                + ", pg_temp");
  }

  /** Reads the unqualified retention settings table through the pinned search_path. */
  @Test
  void retentionFunctionsRunAgainstTheTargetSchema() throws SQLException {
    strings("SELECT * FROM opaa_audit_delete_expired_partitions()");
    strings("SELECT * FROM opaa_diagnostic_context_delete_expired_partitions()");

    assertThat(strings("SELECT last_run_month::text FROM audit_retention_settings")).hasSize(1);
  }

  @Test
  void buildsTheVectorStoreIndexesInTheTargetSchemaOnceTheTableExists() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE vector_store (id uuid PRIMARY KEY, content text, metadata jsonb,"
              + " embedding vector(3))");
    }

    applyMasterChangelog();

    assertThat(
            strings(
                "SELECT indexname FROM pg_indexes WHERE schemaname = '"
                    + SCHEMA
                    + "' AND tablename = 'vector_store' AND indexname LIKE 'idx_%'"))
        .containsExactlyInAnyOrder("idx_vector_store_library_id", "idx_vector_store_document_id");
  }

  private void applyMasterChangelog() throws Exception {
    Database database = liquibaseDatabase(connection);
    database.setDefaultSchemaName(SCHEMA);
    new Liquibase(MASTER_CHANGELOG, new ClassLoaderResourceAccessor(), database)
        .update(new Contexts());
    connection.setAutoCommit(true);
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
