package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.chunk.FullTextChunkStore;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The counting contract {@link FullTextIndexFillState} states, against a real Postgres: a row off
 * {@link FullTextChunkStore#CURRENT_TSV_VERSION} in either direction counts as backlog rather than
 * as indexed, a re-index of the chunk through the ingestion write path clears that backlog again,
 * and a row whose chunk is gone counts at all.
 */
@OpaaIntegrationTest
class FullTextIndexFillStateServiceIntegrationTest {

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
  void aRowBelowTheCurrentTsvVersionCountsAsBacklogRatherThanAsIndexed() {
    indexChunk("Gebührenbefreiung wegen Bedürftigkeit");
    pretendAVersionRaise();

    FullTextIndexFillState fillState = fillStateService.fillStateForLibrary(libraryId);

    assertThat(fillState.totalChunks()).isEqualTo(1);
    assertThat(fillState.indexedChunks()).isZero();
    assertThat(fillState.outdatedChunks()).isEqualTo(1);
    assertThat(fillState.isUpToDate()).isFalse();
    // The grouped read the administration page uses must agree with the single-library one.
    assertThat(fillStateService.fillStateForLibraries(List.of(libraryId)))
        .singleElement()
        .satisfies(
            grouped -> {
              assertThat(grouped.totalChunks()).isEqualTo(1);
              assertThat(grouped.indexedChunks()).isZero();
              assertThat(grouped.outdatedChunks()).isEqualTo(1);
            });
  }

  /**
   * The backlog of a raised {@code CURRENT_TSV_VERSION} is cleared by re-writing the chunk through
   * the same write path the pipeline re-index uses - and by nothing else (#1270).
   */
  @Test
  void reindexingTheChunkClearsTheBacklog() {
    indexChunk("Gebührenbefreiung wegen Bedürftigkeit");
    pretendAVersionRaise();
    assertThat(fillStateService.fillStateForLibrary(libraryId).outdatedChunks()).isEqualTo(1);

    vectorChunkStore.deleteByDocumentId(documentId);
    indexChunk("Gebührenbefreiung wegen Bedürftigkeit");

    FullTextIndexFillState fillState = fillStateService.fillStateForLibrary(libraryId);
    assertThat(fillState.totalChunks()).isEqualTo(1);
    assertThat(fillState.indexedChunks()).isEqualTo(1);
    assertThat(fillState.outdatedChunks()).isZero();
    assertThat(fillState.isUpToDate()).isTrue();
  }

  /** A rollback leaves rows above the current version; they need the same re-index. */
  @Test
  void aRowAboveTheCurrentTsvVersionCountsAsBacklogAsWell() {
    indexChunk("Gebührenbefreiung wegen Bedürftigkeit");
    setTsvVersion((short) (FullTextChunkStore.CURRENT_TSV_VERSION + 1));

    FullTextIndexFillState fillState = fillStateService.fillStateForLibrary(libraryId);

    assertThat(fillState.indexedChunks()).isZero();
    assertThat(fillState.outdatedChunks()).isEqualTo(1);
    assertThat(fillState.isUpToDate()).isFalse();
  }

  /**
   * A {@code chunk_full_text} row whose chunk is gone - what a delete that failed between its two
   * stores leaves behind - counts nowhere. Counted, it would hold its library in a backlog the
   * pipeline re-index cannot clear: that run selects over {@code vector_store}, where the row's
   * chunk no longer is.
   */
  @Test
  void aRowWhoseChunkIsGoneCountsNeitherAsIndexedNorAsBacklog() {
    indexChunk("Gebührenbefreiung wegen Bedürftigkeit");
    UUID orphanedChunkId =
        jdbcTemplate.queryForObject(
            "SELECT chunk_id FROM chunk_full_text WHERE library_id = ?", UUID.class, libraryId);
    setTsvVersion((short) (FullTextChunkStore.CURRENT_TSV_VERSION - 1));
    // Deletes the vector row alone, so the full-text row outlives its chunk - the residual risk
    // VectorChunkStore's non-transactional delete accepts.
    jdbcTemplate.update("DELETE FROM public.vector_store WHERE id = ?", orphanedChunkId);

    FullTextIndexFillState fillState = fillStateService.fillStateForLibrary(libraryId);

    assertThat(fillState.totalChunks()).isZero();
    assertThat(fillState.indexedChunks()).isZero();
    assertThat(fillState.outdatedChunks()).isZero();
    assertThat(fillState.isUpToDate()).isTrue();
    // The grouped read drops the library entirely, exactly as it drops one without any chunk -
    // the caller supplies the zero state for it (see SearchStatusService#libraryStatus).
    assertThat(fillStateService.fillStateForLibraries(List.of(libraryId))).isEmpty();
  }

  /** One chunk through the production write path: vector row and full-text row in one go. */
  private void indexChunk(String text) {
    Document chunk =
        new Document(
            text,
            Map.of(
                VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString(),
                VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryId.toString()));
    vectorChunkStore.addChunks(List.of(chunk));
  }

  /** What a raised {@link FullTextChunkStore#CURRENT_TSV_VERSION} does to existing rows. */
  private void pretendAVersionRaise() {
    setTsvVersion((short) (FullTextChunkStore.CURRENT_TSV_VERSION - 1));
  }

  private void setTsvVersion(short version) {
    jdbcTemplate.update(
        "UPDATE chunk_full_text SET content_tsv_version = ? WHERE library_id = ?",
        version,
        libraryId);
  }
}
