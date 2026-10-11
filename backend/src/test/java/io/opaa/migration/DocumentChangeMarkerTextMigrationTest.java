package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code documents.last_modified_remote} becomes {@code text}: applied to an existing installation,
 * a stored marker of the former full width keeps its value and a longer one fits.
 */
class DocumentChangeMarkerTextMigrationTest extends AbstractBaselineTest {

  private static final String FILE =
      "db/changelog/knowledge/2026-10-11-document-change-marker-text.yaml";

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.filesExcept(FILE);
  }

  @Test
  void theColumnBecomesTextAndAStoredMarkerOfTheFormerWidthSurvives() throws Exception {
    UUID document = insertDocument(insertLibrary("DROPBOX", null), "dropbox:/a.txt");
    String stored = "m:" + "1".repeat(62);
    assertThat(stored).hasSize(64);
    setMarker(document, stored);
    assertThat(columnType()).isEqualTo("character varying");

    applyChangelog(connection, FILE);

    assertThat(columnType()).isEqualTo("text");
    assertThat(markerOf(document)).isEqualTo(stored);
  }

  @Test
  void aDropboxContentHashMarkerFits() throws Exception {
    applyChangelog(connection, FILE);
    UUID document = insertDocument(insertLibrary("DROPBOX", null), "dropbox:/b.txt");
    String marker = "h:" + "ab".repeat(32);
    assertThat(marker).hasSize(66);

    setMarker(document, marker);

    assertThat(markerOf(document)).isEqualTo(marker);
  }

  private void setMarker(UUID document, String marker) throws Exception {
    execute(
        "UPDATE documents SET last_modified_remote = '"
            + marker
            + "' WHERE id = '"
            + document
            + "'");
  }

  private String markerOf(UUID document) throws Exception {
    return stringOf("SELECT last_modified_remote FROM documents WHERE id = '" + document + "'");
  }

  private String columnType() throws Exception {
    return stringOf(
        "SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND table_name = 'documents' AND column_name = 'last_modified_remote'");
  }
}
