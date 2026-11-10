package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.organization.Organization;
import io.opaa.test.BareLibraries;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The hourly removal of chunks whose document row is gone: a chunk set goes from both stores once
 * two runs in a row found it orphaned, the chunks of a document that still exists stay, and the
 * proposed values of rejected model extractions are cleared.
 */
@OpaaIntegrationTest
class OrphanedChunkCleanupIntegrationTest {

  @Autowired private OrphanedChunkCleanup cleanup;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final UUID libraryId = UUID.randomUUID();
  private final UUID existingDocument = UUID.randomUUID();
  private final UUID deletedDocument = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    BareLibraries.create(jdbcTemplate, libraryId);
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id,"
            + " created_at) VALUES (?, 'bleibt.txt', 'bleibt.txt', 'text/plain', 1, 1, now(), ?,"
            + " 'INDEXED', 'UPLOAD', ?, ?, now())",
        existingDocument,
        "checksum-" + existingDocument,
        libraryId,
        Organization.DEFAULT_ID);
  }

  @AfterEach
  void tearDown() {
    vectorChunkStore.deleteByLibraryId(libraryId);
    jdbcTemplate.update("DELETE FROM metadata_model_rejections WHERE library_id = ?", libraryId);
    BareLibraries.remove(jdbcTemplate, libraryId);
  }

  @Test
  void removesTheChunksOfADeletedDocumentOnTheSecondSightingAndKeepsThoseOfAnExistingOne() {
    seed(existingDocument, "Vermerk, der bleibt.");
    seed(deletedDocument, "Vermerk eines geloeschten Dokuments.");

    cleanup.removeOrphans();

    assertThat(vectorChunkStore.remainingOf(UUID.randomUUID(), List.of(deletedDocument)))
        .as("kept after the first sighting")
        .isPositive();

    assertThat(cleanup.removeOrphans()).isGreaterThanOrEqualTo(1);

    assertThat(vectorChunkStore.remainingOf(UUID.randomUUID(), List.of(deletedDocument))).isZero();
    assertThat(vectorChunkStore.remainingOf(UUID.randomUUID(), List.of(existingDocument)))
        .isPositive();
  }

  @Test
  void theHourlyRunClearsEveryStoredProposedValue() {
    UUID rejection = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO metadata_model_rejections (id, library_id, field_key, proposed_value,"
            + " reason) VALUES (?, ?, 'document_type', 'Vorschlag aus dem Inhalt',"
            + " 'OUTSIDE_VOCABULARY')",
        rejection,
        libraryId);

    cleanup.runHourly();

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT proposed_value FROM metadata_model_rejections WHERE id = ?",
                String.class,
                rejection))
        .isNull();
  }

  private void seed(UUID documentId, String text) {
    vectorChunkStore.addChunks(
        List.of(
            new Document(
                text,
                Map.of(
                    VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString(),
                    VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryId.toString()))));
  }
}
