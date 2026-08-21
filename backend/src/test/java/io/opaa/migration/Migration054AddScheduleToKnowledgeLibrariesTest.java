package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Applies Liquibase changelog 054 in isolation against a database built from {@code
 * test-master-through-046.yaml} - the same "siblings stop at 046, independently" fixture {@code
 * Migration049BindIndexingJobsToOrganizationTest} already uses (see that class's Javadoc): 054
 * needs nothing from 047-050 (user/chat/indexing-job organization binding, dropping a redundant
 * space memberships FK), only {@code knowledge_libraries.source_type} from migration 027, already
 * present at 046.
 *
 * <p>Covers what {@code KnowledgeLibraryServiceIntegrationTest}'s application-level tests cannot:
 * that {@code chk_knowledge_libraries_schedule} rejects an invalid combination at the database
 * level, not only in {@code KnowledgeLibraryService#validateSchedule} - in particular the "nur
 * Konnektorbibliotheken" rule (#485, Zuschnitt 21.08.2026: a schedule on a {@code UPLOAD} library
 * is rejected even if it somehow bypassed the service layer), and the rollback path.
 */
class Migration054AddScheduleToKnowledgeLibrariesTest extends AbstractMigrationTest {

  private static final String SEEDED_ORGANIZATION_ID = "00000000-0000-0000-0000-000000000001";

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-046.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void acceptsAnUploadLibraryWithNoSchedule() throws Exception {
    applyChangelog054();

    UUID library = insertLibrary("UPLOAD", false, null);

    assertThat(scheduleEnabled(library)).isFalse();
  }

  @Test
  void rejectsAnUploadLibraryWithAnEnabledSchedule() throws Exception {
    // #485, Zuschnitt 21.08.2026: "nur Konnektorbibliotheken" - enforced at the database level,
    // not only by KnowledgeLibraryService#validateSchedule's 400-before-insert.
    applyChangelog054();

    assertThatThrownBy(() -> insertLibrary("UPLOAD", true, "0 0 * * * *"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_schedule");
  }

  @Test
  void acceptsAConnectorLibraryWithAnEnabledSchedule() throws Exception {
    applyChangelog054();

    UUID library = insertLibrary("FILESYSTEM", true, "0 0 3 * * *");

    assertThat(scheduleEnabled(library)).isTrue();
    assertThat(scheduleCron(library)).isEqualTo("0 0 3 * * *");
  }

  @Test
  void rejectsAnEnabledScheduleWithoutACronExpression() throws Exception {
    applyChangelog054();

    assertThatThrownBy(() -> insertLibrary("HTTP_DIRECTORY", true, null))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_schedule");
  }

  @Test
  void rejectsADisabledScheduleThatStillCarriesACronExpression() throws Exception {
    applyChangelog054();

    assertThatThrownBy(() -> insertLibrary("HTTP_DIRECTORY", false, "0 0 3 * * *"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_schedule");
  }

  @Test
  void rollbackDropsTheScheduleColumnsAndConstraint() throws Exception {
    applyChangelog054();
    assertThat(columnExists("schedule_cron")).isTrue();

    Liquibase liquibase =
        new Liquibase(
            "db/changelog/changes/054-add-schedule-to-knowledge-libraries.yaml",
            new ClassLoaderResourceAccessor(),
            liquibaseDatabase(connection));
    liquibase.rollback(1, (String) null);
    connection.setAutoCommit(true);

    assertThat(columnExists("schedule_cron")).isFalse();
    assertThat(columnExists("schedule_enabled")).isFalse();
  }

  private void applyChangelog054() throws Exception {
    applyChangelog(connection, "db/changelog/changes/054-add-schedule-to-knowledge-libraries.yaml");
  }

  private UUID insertLibrary(String sourceType, boolean scheduleEnabled, String scheduleCron)
      throws SQLException {
    UUID id = UUID.randomUUID();
    UUID ownerUserId = insertUser();
    boolean needsUrl = sourceType.equals("HTTP_DIRECTORY") || sourceType.equals("RSS_FEED");
    boolean needsPath = sourceType.equals("FILESYSTEM");
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
              + " owner_user_id, visibility, listed, source_type"
              + (needsPath ? ", source_path" : "")
              + (needsUrl ? ", source_url" : "")
              + ", schedule_enabled, schedule_cron, created_at, updated_at) VALUES ('"
              + id
              + "', '"
              + SEEDED_ORGANIZATION_ID
              + "', 'Bibliothek "
              + id
              + "', 'USER', '"
              + ownerUserId
              + "', 'PRIVATE', false, '"
              + sourceType
              + "'"
              + (needsPath ? ", '/data/documents'" : "")
              + (needsUrl ? ", 'https://example.com/'" : "")
              + ", "
              + scheduleEnabled
              + ", "
              + (scheduleCron == null ? "NULL" : "'" + scheduleCron + "'")
              + ", now(), now())");
    }
    return id;
  }

  private UUID insertUser() throws SQLException {
    UUID id = UUID.randomUUID();
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO users (id, subject, issuer, email, display_name, created_at,"
              + " organization_id) VALUES ('"
              + id
              + "', '"
              + id
              + "', 'opaa-test', '"
              + id
              + "@example.com', 'Test-Nutzer', now(), '"
              + SEEDED_ORGANIZATION_ID
              + "')");
    }
    return id;
  }

  private boolean scheduleEnabled(UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT schedule_enabled FROM knowledge_libraries WHERE id = '"
                    + libraryId
                    + "'")) {
      result.next();
      return result.getBoolean(1);
    }
  }

  private String scheduleCron(UUID libraryId) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT schedule_cron FROM knowledge_libraries WHERE id = '" + libraryId + "'")) {
      result.next();
      return result.getString(1);
    }
  }

  private boolean columnExists(String column) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT column_name FROM information_schema.columns WHERE table_name ="
                    + " 'knowledge_libraries' AND column_name = '"
                    + column
                    + "'")) {
      return result.next();
    }
  }
}
