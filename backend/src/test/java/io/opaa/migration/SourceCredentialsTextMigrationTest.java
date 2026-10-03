package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code knowledge_libraries.source_credentials} becomes {@code text} (ADR-0040, Entscheidung 2):
 * applied to an existing installation, a stored ciphertext keeps its value and a value beyond the
 * former 3000 characters fits.
 */
class SourceCredentialsTextMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/knowledge/2026-10-03-source-credentials-text.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void theColumnBecomesTextAndAStoredValueSurvives() throws Exception {
    UUID library = insertLibrary("HTTP_DIRECTORY", "https://files.example.com/");
    String stored = "enc:v1:" + "A".repeat(2990);
    execute(
        "UPDATE knowledge_libraries SET source_credentials = '"
            + stored
            + "' WHERE id = '"
            + library
            + "'");
    assertThat(columnType()).isEqualTo("character varying");

    applyChangelog(connection, FILE);

    assertThat(columnType()).isEqualTo("text");
    assertThat(
            stringOf(
                "SELECT source_credentials FROM knowledge_libraries WHERE id = '" + library + "'"))
        .isEqualTo(stored);
  }

  @Test
  void aValueBeyondTheFormerWidthFits() throws Exception {
    applyChangelog(connection, FILE);
    UUID library = insertLibrary("HTTP_DIRECTORY", "https://files.example.com/");
    String wide = "enc:v1:" + "B".repeat(5600);

    execute(
        "UPDATE knowledge_libraries SET source_credentials = '"
            + wide
            + "' WHERE id = '"
            + library
            + "'");

    assertThat(
            longOf(
                "SELECT length(source_credentials) FROM knowledge_libraries WHERE id = '"
                    + library
                    + "'"))
        .isEqualTo(wide.length());
  }

  private String columnType() throws Exception {
    return stringOf(
        "SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND table_name = 'knowledge_libraries' AND column_name = 'source_credentials'");
  }
}
