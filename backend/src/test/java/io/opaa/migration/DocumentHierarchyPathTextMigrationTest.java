package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code documents.source_hierarchy_path} becomes {@code text}: applied to an existing
 * installation, a stored path of the former full width keeps its value and a longer one fits.
 */
class DocumentHierarchyPathTextMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/knowledge/2026-10-04-document-hierarchy-path-text.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void theColumnBecomesTextAndAStoredPathOfTheFormerWidthSurvives() throws Exception {
    UUID document =
        insertDocument(insertLibrary("HTTP_DIRECTORY", "https://files.example.com/"), "/a.txt");
    String stored = "a / ".repeat(500);
    assertThat(stored).hasSize(2000);
    setPath(document, stored);
    assertThat(columnType()).isEqualTo("character varying");

    applyChangelog(connection, FILE);

    assertThat(columnType()).isEqualTo("text");
    assertThat(pathOf(document)).isEqualTo(stored);
  }

  @Test
  void aPathBeyondTheFormerWidthFits() throws Exception {
    applyChangelog(connection, FILE);
    UUID document =
        insertDocument(insertLibrary("HTTP_DIRECTORY", "https://files.example.com/"), "/b.txt");
    String wide = "b".repeat(2100);

    setPath(document, wide);

    assertThat(pathOf(document)).isEqualTo(wide);
  }

  private void setPath(UUID document, String path) throws Exception {
    execute(
        "UPDATE documents SET source_hierarchy_path = '"
            + path
            + "' WHERE id = '"
            + document
            + "'");
  }

  private String pathOf(UUID document) throws Exception {
    return stringOf("SELECT source_hierarchy_path FROM documents WHERE id = '" + document + "'");
  }

  private String columnType() throws Exception {
    return stringOf(
        "SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND table_name = 'documents' AND column_name = 'source_hierarchy_path'");
  }
}
