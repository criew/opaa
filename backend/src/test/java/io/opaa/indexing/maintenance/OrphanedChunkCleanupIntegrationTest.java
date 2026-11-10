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
 * The start-up removal of chunks whose document row is gone: they go from both stores, the chunks
 * of a document that still exists stay.
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
    BareLibraries.remove(jdbcTemplate, libraryId);
  }

  @Test
  void removesTheChunksOfADeletedDocumentAndKeepsThoseOfAnExistingOne() {
    seed(existingDocument, "Vermerk, der bleibt.");
    seed(deletedDocument, "Vermerk eines geloeschten Dokuments.");

    assertThat(cleanup.removeAll()).isGreaterThanOrEqualTo(1);

    assertThat(vectorChunkStore.remainingOf(UUID.randomUUID(), List.of(deletedDocument))).isZero();
    assertThat(vectorChunkStore.remainingOf(UUID.randomUUID(), List.of(existingDocument)))
        .isPositive();
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
