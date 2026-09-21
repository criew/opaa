package io.opaa.migration;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Delta tests for {@code changes/070-knowledge-libraries-share-cap-upload-unrestricted.yaml}
 * (#797): an {@code UPLOAD} library can never carry a narrower cap than the delivered default,
 * independent of the write path - the database-level counterpart of {@code
 * KnowledgeLibraryService#updateShareCap}'s own {@code 400}.
 */
class Migration070KnowledgeLibrariesShareCapUploadUnrestrictedTest extends AbstractMigrationTest {

  private static final String SHARE_CAP_PATH =
      "db/changelog/changes/069-knowledge-libraries-share-cap.yaml";

  private static final String CHANGELOG_PATH =
      "db/changelog/changes/070-knowledge-libraries-share-cap-upload-unrestricted.yaml";

  private static final UUID DEFAULT_ORGANIZATION =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private Connection connection;

  @Override
  protected String baseFixtureChangelogPath() {
    return "db/changelog/test-master-through-baseline.yaml";
  }

  @BeforeEach
  void setUp() throws Exception {
    connection = connect();
    connection.setAutoCommit(true);
    applyChangelog(connection, SHARE_CAP_PATH);
  }

  @AfterEach
  void tearDown() throws SQLException {
    connection.close();
  }

  @Test
  void beforeTheChangesetAnUploadLibraryAcceptsARestrictedCap() throws Exception {
    UUID library = seedLibrary("UPLOAD");

    execute("UPDATE knowledge_libraries SET visibility_cap = 'PRIVATE' WHERE id = ?", library);
  }

  @Test
  void afterTheChangesetAnUploadLibraryRejectsARestrictedVisibilityCap() throws Exception {
    UUID library = seedLibrary("UPLOAD");
    applyChangelog(connection, CHANGELOG_PATH);

    assertThatThrownBy(
            () ->
                execute(
                    "UPDATE knowledge_libraries SET visibility_cap = 'PRIVATE' WHERE id = ?",
                    library))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("chk_knowledge_libraries_share_cap_upload_unrestricted");
  }

  @Test
  void afterTheChangesetAConnectorLibraryStillAcceptsARestrictedCap() throws Exception {
    UUID library = seedLibrary("FILESYSTEM");
    applyChangelog(connection, CHANGELOG_PATH);

    execute(
        "UPDATE knowledge_libraries SET visibility_cap = 'PRIVATE', listed_cap = false"
            + " WHERE id = ?",
        library);
  }

  @Test
  void theChangelogIsReferencedByTheMasterChangelog() throws Exception {
    assertThat(masterChangelog()).contains(CHANGELOG_PATH);
  }

  // -----------------------------------------------------------------------------------------

  private UUID seedLibrary(String sourceType) throws SQLException {
    UUID owner = UUID.randomUUID();
    execute(
        "INSERT INTO users (id, subject, issuer, organization_id) VALUES (?, ?, ?, ?)",
        owner,
        "share-cap-upload-" + owner,
        "https://issuer.example",
        DEFAULT_ORGANIZATION);
    UUID library = UUID.randomUUID();
    if ("UPLOAD".equals(sourceType)) {
      execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
              + " owner_user_id, visibility, source_type)"
              + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'UPLOAD')",
          library,
          DEFAULT_ORGANIZATION,
          "Bibliothek",
          owner);
    } else {
      execute(
          "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type,"
              + " owner_user_id, visibility, source_type, source_path)"
              + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', 'FILESYSTEM', '/data/dokumente')",
          library,
          DEFAULT_ORGANIZATION,
          "Bibliothek",
          owner);
    }
    return library;
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.executeUpdate();
    }
  }

  private String masterChangelog() throws Exception {
    return new String(
        requireNonNull(
                getClass()
                    .getClassLoader()
                    .getResourceAsStream("db/changelog/db.changelog-master.yaml"))
            .readAllBytes(),
        StandardCharsets.UTF_8);
  }
}
