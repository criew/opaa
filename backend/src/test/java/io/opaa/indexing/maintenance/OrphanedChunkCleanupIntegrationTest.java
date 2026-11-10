package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.organization.Organization;
import io.opaa.test.BareLibraries;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The nightly removal of chunks whose document row is gone: a chunk set goes from both stores in
 * the first run once a second look after the wait still finds it orphaned, the chunks of a document
 * that exists by then stay, and the proposed values of rejected model extractions are cleared.
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
    insertDocument(existingDocument, "bleibt.txt");
  }

  @AfterEach
  void tearDown() {
    vectorChunkStore.deleteByLibraryId(libraryId);
    jdbcTemplate.update("DELETE FROM metadata_model_rejections WHERE library_id = ?", libraryId);
    BareLibraries.remove(jdbcTemplate, libraryId);
  }

  @Test
  void aFreshlyStartedInstanceRemovesAnOrphanInItsFirstRunAndKeepsTheChunksOfAnExistingDocument()
      throws InterruptedException {
    seed(existingDocument, "Vermerk, der bleibt.");
    seed(deletedDocument, "Vermerk eines geloeschten Dokuments.");

    assertThat(cleanup.removeOrphans(Duration.ZERO)).isGreaterThanOrEqualTo(1);

    assertThat(vectorChunkStore.remainingOf(UUID.randomUUID(), List.of(deletedDocument))).isZero();
    assertThat(vectorChunkStore.remainingOf(UUID.randomUUID(), List.of(existingDocument)))
        .isPositive();
  }

  @Test
  void aChunkSetWhoseDocumentRowAppearsDuringTheWaitStays() throws Exception {
    UUID arriving = UUID.randomUUID();
    seed(arriving, "Vermerk eines Dokuments, dessen Zeile gleich kommt.");

    CompletableFuture<Integer> run =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return cleanup.removeOrphans(Duration.ofSeconds(3));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
              }
            });
    Thread.sleep(500);
    insertDocument(arriving, "kommt.txt");
    run.get(30, TimeUnit.SECONDS);

    assertThat(vectorChunkStore.remainingOf(UUID.randomUUID(), List.of(arriving))).isPositive();
  }

  @Test
  void clearsEveryStoredProposedValue() {
    UUID rejection = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO metadata_model_rejections (id, library_id, field_key, proposed_value,"
            + " reason) VALUES (?, ?, 'document_type', 'Vorschlag aus dem Inhalt',"
            + " 'OUTSIDE_VOCABULARY')",
        rejection,
        libraryId);

    cleanup.clearProposedValues();

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT proposed_value FROM metadata_model_rejections WHERE id = ?",
                String.class,
                rejection))
        .isNull();
  }

  private void insertDocument(UUID documentId, String name) {
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id,"
            + " created_at) VALUES (?, ?, ?, 'text/plain', 1, 1, now(), ?,"
            + " 'INDEXED', 'UPLOAD', ?, ?, now())",
        documentId,
        name,
        name,
        "checksum-" + documentId,
        libraryId,
        Organization.DEFAULT_ID);
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
