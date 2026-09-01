package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The resumability contract of {@link FullTextBackfillService#backfillBatch} (#1047, docs/features/
 * hybrid-retrieval.md "Arbeitspaket 2a": "Wiederaufnehmbar mit persistiertem Fortschritt. Der Lauf
 * darf jederzeit abgebrochen werden ... und setzt danach dort fort, wo er stand, nicht am
 * Anfang.").
 *
 * <p>Chunks here are written directly via {@link VectorStore#add}, bypassing {@link
 * VectorChunkStore} on purpose: that is exactly the state of a chunk written before #1047, or one
 * this backfill has not caught up to yet - the backlog this service exists to drain.
 */
// Own @MockitoBean FullTextBackfillScheduler below (needed so the real, shared-context scheduler's
// own periodic tick cannot race this test's own manual backfillBatch() calls and turn the exact
// row-count assertions flaky) means Spring's context cache still keys this to its own context
// regardless of the shared @OpaaIndexingIntegrationTest base - documented exception per AGENTS.md.
@OpaaIndexingIntegrationTest
class FullTextBackfillServiceIntegrationTest {

  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FullTextBackfillService backfillService;
  @Autowired private FullTextBackfillProgressService progressService;
  @MockitoBean private FullTextBackfillScheduler fullTextBackfillScheduler;

  private final UUID libraryId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
  }

  @Test
  void repeatedSmallBatchesEventuallyIndexEveryChunkWithoutDuplicatesOrGaps() {
    int totalChunks = 5;
    seedUnindexedChunks(totalChunks);

    // Simulates an interrupted-then-resumed run (crash, restart, next scheduler tick): several
    // small batch() calls rather than one call covering the whole backlog. Nothing but the
    // database itself remembers where a previous call left off.
    assertThat(backfillService.backfillBatch(2)).isEqualTo(2);
    assertThat(countFullTextRows()).isEqualTo(2);

    assertThat(backfillService.backfillBatch(2)).isEqualTo(2);
    assertThat(countFullTextRows()).isEqualTo(4);

    // Only one chunk remains - a batch larger than the remaining backlog returns exactly what was
    // left, not the requested batch size.
    assertThat(backfillService.backfillBatch(2)).isEqualTo(1);
    assertThat(countFullTextRows()).isEqualTo(5);

    // Idempotent: once the backlog is empty, further calls are a no-op, not an error - and no
    // duplicate rows appear (chunk_id is the primary key; a duplicate insert would have thrown).
    assertThat(backfillService.backfillBatch(2)).isZero();
    assertThat(countFullTextRows()).isEqualTo(5);
  }

  @Test
  void backfilledContentIsSearchableViaTheGinIndexAfterwards() {
    UUID chunkId = seedUnindexedChunk("Befreiung von der Verwaltungsgebühr wegen Bedürftigkeit");

    int processed = backfillService.backfillBatch(10);

    assertThat(processed).isEqualTo(1);
    Long matches =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk_full_text WHERE chunk_id = ? "
                + "AND content_tsv @@ to_tsquery('german', 'Bedürftigkeit')",
            Long.class,
            chunkId);
    assertThat(matches).isEqualTo(1L);
  }

  @Test
  void progressForLibraryReflectsPartialThenCompleteBackfillState() {
    seedUnindexedChunks(4);

    FullTextBackfillProgress beforeBackfill = progressService.progressForLibrary(libraryId);
    assertThat(beforeBackfill.totalChunks()).isEqualTo(4);
    assertThat(beforeBackfill.indexedChunks()).isZero();
    assertThat(beforeBackfill.isComplete()).isFalse();

    backfillService.backfillBatch(3);

    FullTextBackfillProgress partial = progressService.progressForLibrary(libraryId);
    assertThat(partial.totalChunks()).isEqualTo(4);
    assertThat(partial.indexedChunks()).isEqualTo(3);
    assertThat(partial.isComplete()).isFalse();

    backfillService.backfillBatch(10);

    FullTextBackfillProgress complete = progressService.progressForLibrary(libraryId);
    assertThat(complete.indexedChunks()).isEqualTo(4);
    assertThat(complete.isComplete()).isTrue();
  }

  private void seedUnindexedChunks(int count) {
    List<org.springframework.ai.document.Document> chunks =
        IntStream.range(0, count).mapToObj(i -> chunkDocument("chunk text " + i)).toList();
    vectorStore.add(chunks);
  }

  private UUID seedUnindexedChunk(String text) {
    org.springframework.ai.document.Document chunk = chunkDocument(text);
    vectorStore.add(List.of(chunk));
    return UUID.fromString(chunk.getId());
  }

  private org.springframework.ai.document.Document chunkDocument(String text) {
    return new org.springframework.ai.document.Document(
        text,
        Map.of(
            VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString(),
            VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryId.toString()));
  }

  private long countFullTextRows() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM chunk_full_text", Long.class);
  }
}
