package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two counting invariants {@link FullTextIndexFillState}'s Javadoc claims, against a real
 * Postgres: a row below {@link FullTextChunkStore#CURRENT_TSV_VERSION} does not count as indexed,
 * and {@code missingChunks} is an anti-join rather than a subtraction, so an orphaned {@code
 * chunk_full_text} row cannot cancel out a genuinely un-indexed chunk.
 */
@OpaaIndexingIntegrationTest
class FullTextIndexFillStateServiceIntegrationTest {

  @Autowired private VectorStore vectorStore;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private FullTextIndexFillStateService fillStateService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final UUID libraryId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();

  @AfterEach
  void tearDown() {
    vectorChunkStore.deleteByLibraryId(libraryId);
  }

  @Test
  void aRowBelowTheCurrentTsvVersionCountsAsMissingRatherThanIndexed() {
    UUID chunkId = seedVectorChunkWithoutFullTextRow("Gebührenbefreiung wegen Bedürftigkeit");
    insertFullTextRow(chunkId, (short) (FullTextChunkStore.CURRENT_TSV_VERSION - 1));

    FullTextIndexFillState fillState = fillStateService.fillStateForLibrary(libraryId);

    assertThat(fillState.totalChunks()).isEqualTo(1);
    assertThat(fillState.indexedChunks()).isZero();
    assertThat(fillState.missingChunks()).isEqualTo(1);
    assertThat(fillState.isComplete()).isFalse();
    // The grouped read the administration page uses must agree with the single-library one.
    assertThat(fillStateService.fillStateForLibraries(List.of(libraryId)))
        .singleElement()
        .satisfies(
            grouped -> {
              assertThat(grouped.indexedChunks()).isZero();
              assertThat(grouped.missingChunks()).isEqualTo(1);
            });
  }

  @Test
  void anOrphanedFullTextRowDoesNotMaskAGenuinelyMissingChunk() {
    seedVectorChunkWithoutFullTextRow("Ein Abschnitt ohne Volltextzeile");
    // A chunk_full_text row for an id no vector_store row carries - left behind by a bug, or by a
    // chunk deleted through a path that bypassed VectorChunkStore.
    insertFullTextRow(UUID.randomUUID(), FullTextChunkStore.CURRENT_TSV_VERSION);

    FullTextIndexFillState fillState = fillStateService.fillStateForLibrary(libraryId);

    // totalChunks (1) minus indexedChunks (1, the orphan) would read "complete"; the anti-join
    // still reports the one vector chunk that has no row of its own.
    assertThat(fillState.totalChunks()).isEqualTo(1);
    assertThat(fillState.indexedChunks()).isEqualTo(1);
    assertThat(fillState.missingChunks()).isEqualTo(1);
    assertThat(fillState.isComplete()).isFalse();
  }

  /** Written straight into the vector store, so no {@code chunk_full_text} row exists for it. */
  private UUID seedVectorChunkWithoutFullTextRow(String text) {
    Document chunk =
        new Document(
            text,
            Map.of(
                VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString(),
                VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryId.toString()));
    vectorStore.add(List.of(chunk));
    return UUID.fromString(chunk.getId());
  }

  private void insertFullTextRow(UUID chunkId, short contentTsvVersion) {
    jdbcTemplate.update(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv, "
            + "content_tsv_version) VALUES (?, ?, ?, to_tsvector('german', 'inhalt'), ?)",
        chunkId,
        documentId,
        libraryId,
        contentTsvVersion);
  }
}
