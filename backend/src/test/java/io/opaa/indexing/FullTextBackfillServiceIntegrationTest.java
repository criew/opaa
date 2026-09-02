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

/**
 * The resumability contract of {@link FullTextBackfillService#backfillBatch} (#1047, docs/features/
 * hybrid-retrieval.md "Arbeitspaket 2a": "Wiederaufnehmbar mit persistiertem Fortschritt. Der Lauf
 * darf jederzeit abgebrochen werden ... und setzt danach dort fort, wo er stand, nicht am
 * Anfang.").
 *
 * <p>Chunks here are written directly via {@link VectorStore#add}, bypassing {@link
 * VectorChunkStore} on purpose: that is exactly the state of a chunk written before #1047, or one
 * this backfill has not caught up to yet - the backlog this service exists to drain.
 *
 * <p>No {@code @MockitoBean} needed to silence {@link FullTextBackfillScheduler}'s own tick (#1047
 * review, finding 8): {@code @OpaaIndexingIntegrationTest} fixes {@code
 * opaa.indexing.full-text-backfill.tick-ms} high enough that it never fires during a test run, so
 * this class shares the ordinary {@code @OpaaIndexingIntegrationTest} context like any other class
 * carrying that meta-annotation.
 */
@OpaaIndexingIntegrationTest
class FullTextBackfillServiceIntegrationTest {

  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FullTextBackfillService backfillService;
  @Autowired private FullTextBackfillProgressService progressService;
  @Autowired private VectorChunkStore vectorChunkStore;

  private final UUID libraryId = UUID.randomUUID();
  private final UUID documentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text, chunk_full_text_skip");
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
    assertThat(beforeBackfill.missingChunks()).isEqualTo(4);
    assertThat(beforeBackfill.isComplete()).isFalse();

    backfillService.backfillBatch(3);

    FullTextBackfillProgress partial = progressService.progressForLibrary(libraryId);
    assertThat(partial.totalChunks()).isEqualTo(4);
    assertThat(partial.indexedChunks()).isEqualTo(3);
    assertThat(partial.missingChunks()).isEqualTo(1);
    assertThat(partial.isComplete()).isFalse();

    backfillService.backfillBatch(10);

    FullTextBackfillProgress complete = progressService.progressForLibrary(libraryId);
    assertThat(complete.indexedChunks()).isEqualTo(4);
    assertThat(complete.missingChunks()).isZero();
    assertThat(complete.isComplete()).isTrue();
  }

  /**
   * #1047 review, finding 2: a {@code chunk_full_text} row with no matching {@code vector_store}
   * row (e.g. left behind by a bug, or by a chunk deleted through a path that skipped {@link
   * VectorChunkStore}) must never make {@link FullTextBackfillProgress#isComplete()} look complete
   * for chunks that are genuinely still missing - {@code indexedChunks} and {@code totalChunks}
   * alone cannot tell the two situations apart, only the direct {@code missingChunks} anti-join
   * can.
   */
  @Test
  void anOrphanedFullTextRowDoesNotMaskAGenuinelyMissingChunk() {
    seedUnindexedChunks(2);
    backfillService.backfillBatch(1);
    // One chunk deliberately left un-backfilled; simulate an orphaned chunk_full_text row for an
    // id that has no vector_store counterpart at all (never produced by this backfill's own
    // insert, since it only ever inserts ids it selected from vector_store).
    jdbcTemplate.update(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv, "
            + "content_tsv_version) VALUES (?, ?, ?, to_tsvector('german', 'orphan'), ?)",
        UUID.randomUUID(),
        documentId,
        libraryId,
        FullTextChunkStore.CURRENT_TSV_VERSION);

    FullTextBackfillProgress progress = progressService.progressForLibrary(libraryId);

    // totalChunks (2) vs indexedChunks (1 real + 1 orphan = 2) would wrongly read "complete" if
    // isComplete() compared those two counts directly - missingChunks (the anti-join) still
    // correctly reports the one vector_store chunk that has no chunk_full_text row.
    assertThat(progress.totalChunks()).isEqualTo(2);
    assertThat(progress.indexedChunks()).isEqualTo(2);
    assertThat(progress.missingChunks()).isEqualTo(1);
    assertThat(progress.isComplete()).isFalse();
  }

  /**
   * A stale-version row is selected by {@link FullTextBackfillService#backfillBatch} exactly like a
   * missing one, and {@code ON CONFLICT (chunk_id) DO UPDATE} actually brings it up to date - not
   * {@code DO NOTHING}, which would have left it stale and reselected forever.
   */
  @Test
  void aRowAtAnOlderContentTsvVersionIsUpdatedNotSkipped() {
    UUID chunkId = seedUnindexedChunk("Befreiung von der Verwaltungsgebühr wegen Bedürftigkeit");
    jdbcTemplate.update(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv, "
            + "content_tsv_version) VALUES (?, ?, ?, to_tsvector('german', 'stale content'), 0)",
        chunkId,
        documentId,
        libraryId);

    FullTextBackfillProgress beforeBackfill = progressService.progressForLibrary(libraryId);
    assertThat(beforeBackfill.missingChunks()).isEqualTo(1);
    assertThat(beforeBackfill.isComplete()).isFalse();

    int processed = backfillService.backfillBatch(10);

    assertThat(processed).isEqualTo(1);
    Short version =
        jdbcTemplate.queryForObject(
            "SELECT content_tsv_version FROM chunk_full_text WHERE chunk_id = ?",
            Short.class,
            chunkId);
    assertThat(version).isEqualTo(FullTextChunkStore.CURRENT_TSV_VERSION);
    Long matches =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk_full_text WHERE chunk_id = ? "
                + "AND content_tsv @@ to_tsquery('german', 'Bedürftigkeit')",
            Long.class,
            chunkId);
    assertThat(matches).isEqualTo(1L);

    FullTextBackfillProgress afterBackfill = progressService.progressForLibrary(libraryId);
    assertThat(afterBackfill.missingChunks()).isZero();
    assertThat(afterBackfill.isComplete()).isTrue();
  }

  /**
   * The version scaffolding doing the job it was built for (#1048): a row written under version 1 -
   * the exact state every chunk indexed before the identifier protection is in - carries no
   * undecomposed identifier lexeme and would silently miss every identifier query. The backfill
   * treats it as missing and rebuilds it, without anyone having to write a migration or a one-off
   * script. The row is planted with the pre-#1048 {@code content_tsv} expression on purpose: an
   * assertion against a row that already had the lexemes would hold before and after the bump and
   * prove nothing.
   */
  @Test
  void aRowWrittenBeforeTheIdentifierProtectionIsRebuiltWithItsIdentifierLexemes() {
    UUID chunkId = seedUnindexedChunk("Zulässig im Außenbereich nach § 35 BauGB.");
    jdbcTemplate.update(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv, "
            + "content_tsv_version) VALUES (?, ?, ?, to_tsvector('german', ?), 1)",
        chunkId,
        documentId,
        libraryId,
        "Zulässig im Außenbereich nach § 35 BauGB.");

    assertThat(identifierMatches(chunkId)).isZero();
    assertThat(progressService.progressForLibrary(libraryId).isComplete()).isFalse();

    assertThat(backfillService.backfillBatch(10)).isEqualTo(1);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT content_tsv_version FROM chunk_full_text WHERE chunk_id = ?",
                Short.class,
                chunkId))
        .isEqualTo(FullTextChunkStore.CURRENT_TSV_VERSION);
    assertThat(identifierMatches(chunkId)).isEqualTo(1L);
    assertThat(progressService.progressForLibrary(libraryId).isComplete()).isTrue();
  }

  /**
   * The same mechanism for the second version bump: version 2 already carried identifier lexemes,
   * but not the keyword-free administrative file numbers version 3 added. A row at the older
   * version is rebuilt rather than left behind - the assertion that keeps a widened pattern list
   * from taking effect on new chunks only.
   */
  @Test
  void aRowAtThePreviousIdentifierVersionIsRebuiltWithTheWidenedPatternList() {
    String text = "Diese Dienstanweisung BAU-DA-2/2024 regelt die Bearbeitung.";
    UUID chunkId = seedUnindexedChunk(text);
    jdbcTemplate.update(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv, "
            + "content_tsv_version) VALUES (?, ?, ?, to_tsvector('german', ?), 2)",
        chunkId,
        documentId,
        libraryId,
        text);

    assertThat(matchesLexeme(chunkId, "xakzbauda22024")).isZero();

    assertThat(backfillService.backfillBatch(10)).isEqualTo(1);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT content_tsv_version FROM chunk_full_text WHERE chunk_id = ?",
                Short.class,
                chunkId))
        .isEqualTo(FullTextChunkStore.CURRENT_TSV_VERSION);
    assertThat(matchesLexeme(chunkId, "xakzbauda22024")).isEqualTo(1L);
  }

  /**
   * Reproduction for #1093: a chunk whose {@code content_tsv} PostgreSQL itself refuses to build
   * ("string is too long for tsvector", SQLSTATE 54000/program_limit_exceeded - a genuine, not
   * simulated, {@code to_tsvector} failure) must not take the rest of the batch down with it, and
   * must not be given up on after a single attempt (#1093 review, Blocker 1) - it only becomes a
   * confirmed, permanent skip after {@link FullTextBackfillService#SKIP_CONFIRMATION_ATTEMPTS}
   * consecutive failures. Seeded directly into {@code vector_store} (bypassing {@link
   * VectorStore#add}/the embedding model's own token-count guard) - exactly the state of a chunk
   * written by an older pipeline version that did not yet reject such content, which is the
   * realistic way a poison chunk like this could exist in the first place.
   */
  @Test
  void aPoisonChunkIsIsolatedAndSkippedWithoutBlockingHealthyChunksInTheSameBatch() {
    UUID healthyChunkA = seedUnindexedChunk("Befreiung von der Verwaltungsgebühr");
    UUID healthyChunkB = seedUnindexedChunk("Zulässig im Außenbereich nach § 35 BauGB");
    UUID poisonChunk = seedPoisonChunkExceedingTheTsvectorSizeLimit();

    // First attempt: the healthy chunks index immediately; the poison chunk is isolated and its
    // first failed attempt recorded, but not yet confirmed - it stays part of the pending
    // backlog rather than being given up on after a single failure.
    int firstResolved = backfillService.backfillBatch(10);
    assertThat(firstResolved).isEqualTo(3);
    assertThat(isIndexed(healthyChunkA)).isTrue();
    assertThat(isIndexed(healthyChunkB)).isTrue();
    assertThat(isIndexed(poisonChunk)).isFalse();
    assertThat(attempts(poisonChunk)).isEqualTo(1);
    assertThat(isConfirmedSkipped(poisonChunk)).isFalse();
    assertThat(progressService.progressForLibrary(libraryId).isComplete()).isFalse();

    // Two more failed attempts (three total) confirm it as a permanent skip.
    for (int i = 1; i < FullTextBackfillService.SKIP_CONFIRMATION_ATTEMPTS; i++) {
      assertThat(backfillService.backfillBatch(10)).isEqualTo(1);
    }
    assertThat(attempts(poisonChunk)).isEqualTo(FullTextBackfillService.SKIP_CONFIRMATION_ATTEMPTS);
    assertThat(isConfirmedSkipped(poisonChunk)).isTrue();

    FullTextBackfillProgress progress = progressService.progressForLibrary(libraryId);
    assertThat(progress.totalChunks()).isEqualTo(3);
    assertThat(progress.indexedChunks()).isEqualTo(2);
    assertThat(progress.missingChunks()).isZero();
    assertThat(progress.skippedChunks()).isEqualTo(1);
    // The gate must not hold the library's two healthy, already-searchable chunks hostage to the
    // one it will never be able to index - see FullTextBackfillProgress#isComplete's own Javadoc.
    assertThat(progress.isComplete()).isTrue();
  }

  /**
   * A confirmed, permanently skipped chunk is not retried on every future tick - it would otherwise
   * cost a failing batch, and therefore isolation work, forever.
   */
  @Test
  void aConfirmedPermanentlySkippedChunkIsNeverSelectedAgain() {
    seedPoisonChunkExceedingTheTsvectorSizeLimit();
    for (int i = 0; i < FullTextBackfillService.SKIP_CONFIRMATION_ATTEMPTS; i++) {
      assertThat(backfillService.backfillBatch(10)).isEqualTo(1);
    }

    assertThat(backfillService.backfillBatch(10)).isZero();
  }

  /**
   * The mechanism that keeps a skip from being permanent in the wrong sense (#1093): a
   * <em>confirmed</em> skip ({@code attempts >= }{@link
   * FullTextBackfillService#SKIP_CONFIRMATION_ATTEMPTS}) recorded at an older {@code
   * content_tsv_version} - simulating one that predates a lexical analysis chain change, exactly
   * like a stale {@code chunk_full_text} row - no longer suppresses the chunk once the current
   * version has moved past it. This is the same version-based invalidation {@link
   * FullTextChunkStore#CURRENT_TSV_VERSION}'s own Javadoc already documents for {@code
   * chunk_full_text} itself, applied identically to {@code chunk_full_text_skip}.
   */
  @Test
  void aConfirmedSkipAtAnOlderVersionIsEligibleForRetryAfterAVersionBump() {
    UUID chunkId = seedUnindexedChunk("Befreiung von der Verwaltungsgebühr wegen Bedürftigkeit");
    jdbcTemplate.update(
        "INSERT INTO chunk_full_text_skip (chunk_id, document_id, library_id, "
            + "content_tsv_version, error_message, attempts) VALUES (?, ?, ?, ?, ?, ?)",
        chunkId,
        documentId,
        libraryId,
        (short) (FullTextChunkStore.CURRENT_TSV_VERSION - 1),
        "simulated stale, confirmed skip from a previous content_tsv_version",
        FullTextBackfillService.SKIP_CONFIRMATION_ATTEMPTS);

    int resolved = backfillService.backfillBatch(10);

    assertThat(resolved).isEqualTo(1);
    assertThat(isIndexed(chunkId)).isTrue();
  }

  /**
   * #1093 review, W1: {@code indexedChunks} must be filtered to {@link
   * FullTextChunkStore#CURRENT_TSV_VERSION} like every other count here - otherwise a chunk indexed
   * at an older version, then reselected after a version bump and confirmed-skipped this time,
   * would count in both {@code indexedChunks} (its stale row) and {@code skippedChunks} (its new
   * skip row), letting {@code indexed + skipped} exceed {@code totalChunks}.
   */
  @Test
  void indexedChunksDoesNotDoubleCountAStaleRowAlongsideAConfirmedSkipAtTheCurrentVersion() {
    UUID chunkId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO chunk_full_text (chunk_id, document_id, library_id, content_tsv, "
            + "content_tsv_version) VALUES (?, ?, ?, to_tsvector('german', 'stale'), ?)",
        chunkId,
        documentId,
        libraryId,
        (short) (FullTextChunkStore.CURRENT_TSV_VERSION - 1));
    jdbcTemplate.update(
        "INSERT INTO chunk_full_text_skip (chunk_id, document_id, library_id, "
            + "content_tsv_version, error_message, attempts) VALUES (?, ?, ?, ?, ?, ?)",
        chunkId,
        documentId,
        libraryId,
        FullTextChunkStore.CURRENT_TSV_VERSION,
        "simulated confirmed skip at the current version",
        FullTextBackfillService.SKIP_CONFIRMATION_ATTEMPTS);

    FullTextBackfillProgress progress = progressService.progressForLibrary(libraryId);

    assertThat(progress.indexedChunks()).isZero();
    assertThat(progress.skippedChunks()).isEqualTo(1);
  }

  /**
   * #1093 review, W3: {@code chunk_full_text_skip} must not outlive the chunk it belongs to, the
   * same invariant {@link VectorChunkStore}'s own Javadoc already states for {@code
   * chunk_full_text}. Without this, an operator who deletes and re-indexes a document to fix a
   * poison chunk would find {@code skippedChunks} permanently stuck at its old count.
   */
  @Test
  void deletingTheDocumentClearsItsConfirmedSkipRowsToo() {
    UUID chunkId = seedPoisonChunkExceedingTheTsvectorSizeLimit();
    for (int i = 0; i < FullTextBackfillService.SKIP_CONFIRMATION_ATTEMPTS; i++) {
      backfillService.backfillBatch(10);
    }
    assertThat(isConfirmedSkipped(chunkId)).isTrue();

    vectorChunkStore.deleteByDocumentId(documentId);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunk_full_text_skip WHERE chunk_id = ?",
                Long.class,
                chunkId))
        .isZero();
  }

  /**
   * Mirrors {@link #deletingTheDocumentClearsItsConfirmedSkipRowsToo} for the library-wide delete.
   */
  @Test
  void deletingTheLibraryClearsItsConfirmedSkipRowsToo() {
    UUID chunkId = seedPoisonChunkExceedingTheTsvectorSizeLimit();
    for (int i = 0; i < FullTextBackfillService.SKIP_CONFIRMATION_ATTEMPTS; i++) {
      backfillService.backfillBatch(10);
    }
    assertThat(isConfirmedSkipped(chunkId)).isTrue();

    vectorChunkStore.deleteByLibraryId(libraryId);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunk_full_text_skip WHERE chunk_id = ?",
                Long.class,
                chunkId))
        .isZero();
  }

  private boolean isIndexed(UUID chunkId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk_full_text WHERE chunk_id = ? AND content_tsv_version = ?",
            Long.class,
            chunkId,
            FullTextChunkStore.CURRENT_TSV_VERSION);
    return count == 1L;
  }

  private int attempts(UUID chunkId) {
    Integer attempts =
        jdbcTemplate.queryForObject(
            "SELECT attempts FROM chunk_full_text_skip WHERE chunk_id = ? AND "
                + "content_tsv_version = ?",
            Integer.class,
            chunkId,
            FullTextChunkStore.CURRENT_TSV_VERSION);
    return attempts;
  }

  private boolean isConfirmedSkipped(UUID chunkId) {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM chunk_full_text_skip WHERE chunk_id = ? AND "
                + "content_tsv_version = ? AND attempts >= ?",
            Long.class,
            chunkId,
            FullTextChunkStore.CURRENT_TSV_VERSION,
            FullTextBackfillService.SKIP_CONFIRMATION_ATTEMPTS);
    return count == 1L;
  }

  /**
   * Many DISTINCT words, not one repeated word: {@code to_tsvector} deduplicates identical lexemes,
   * so a single overlong "word" is silently truncated rather than rejected - it takes enough
   * distinct lexemes to push the resulting {@code tsvector} itself past PostgreSQL's 1 MiB size
   * limit to reproduce a genuine {@code to_tsvector} failure.
   */
  private UUID seedPoisonChunkExceedingTheTsvectorSizeLimit() {
    UUID chunkId = UUID.randomUUID();
    StringBuilder content = new StringBuilder();
    for (int i = 0; i < 200_000; i++) {
      content.append("wort").append(i).append(' ');
    }
    String zeroVector = "[" + String.join(",", java.util.Collections.nCopies(1536, "0")) + "]";
    jdbcTemplate.update(
        "INSERT INTO public.vector_store (id, content, metadata, embedding) "
            + "VALUES (?, ?, ?::jsonb, ?::vector)",
        chunkId,
        content.toString(),
        "{\"document_id\":\"" + documentId + "\",\"library_id\":\"" + libraryId + "\"}",
        zeroVector);
    return chunkId;
  }

  private long identifierMatches(UUID chunkId) {
    return matchesLexeme(chunkId, "xpar35baugb");
  }

  private long matchesLexeme(UUID chunkId, String lexeme) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM chunk_full_text WHERE chunk_id = ? "
            + "AND content_tsv @@ to_tsquery('simple', ?)",
        Long.class,
        chunkId,
        lexeme);
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
