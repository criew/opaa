package io.opaa.retrieval.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.metadata.MetadataFilter;
import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RerankAvailability;
import io.opaa.retrieval.RetrievalContext;
import io.opaa.retrieval.RetrievalState;
import io.opaa.retrieval.StageOutcome;
import io.opaa.retrieval.scope.SearchScopeStage;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The vector path against a real HNSW index (docs/handbuch/suche.md, Stufe 4): the permission
 * filter of a narrow search scope and a fetch-k above pgvector's default {@code hnsw.ef_search}
 * (40) must both still yield fetch-k candidates. Without an iterative index scan the index hands
 * out at most {@code ef_search} rows and the filter only thins them out afterwards (regression
 * guard for #2345).
 *
 * <p>The suite's shared {@code vector_store} has no vector index ({@code index-type=none}), so this
 * class builds one for the duration of each method and drops it again. The search runs with {@code
 * enable_seqscan = off}: whether the planner picks the index on its own depends on table size and
 * statistics, and this class is about what happens <em>when</em> it does, as in production.
 */
@OpaaIntegrationTest
class VectorSearchHnswIntegrationTest {

  private static final String INDEX_NAME = "vector_store_hnsw_probe_idx";
  private static final int LIBRARIES = 20;
  private static final int CHUNKS_PER_LIBRARY = 100;
  private static final int SMALL_LIBRARY_CHUNKS = 10;

  @Autowired private SearchScopeStage searchScopeStage;
  @Autowired private VectorSearchStage vectorSearchStage;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  private final List<UUID> libraries = new ArrayList<>();
  private final UUID smallLibrary = UUID.randomUUID();
  private final String marker = UUID.randomUUID().toString();

  @BeforeEach
  void chunksAndAnHnswIndexExist() {
    for (int i = 0; i < LIBRARIES; i++) {
      UUID library = UUID.randomUUID();
      libraries.add(library);
      insertChunks(library, CHUNKS_PER_LIBRARY);
    }
    insertChunks(smallLibrary, SMALL_LIBRARY_CHUNKS);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              // Parallel index builds need more /dev/shm than a test container offers.
              jdbcTemplate.execute("SET LOCAL max_parallel_maintenance_workers = 0");
              jdbcTemplate.execute(
                  "CREATE INDEX "
                      + INDEX_NAME
                      + " ON vector_store USING hnsw (embedding vector_cosine_ops)");
            });
    jdbcTemplate.execute("ANALYZE vector_store");
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.execute("DROP INDEX IF EXISTS " + INDEX_NAME);
    jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'probe_marker' = ?", marker);
  }

  @Test
  void aNarrowSearchScopeStillYieldsFetchKCandidates() {
    List<Document> candidates = onlyList(search(Set.of(libraries.get(0)), 25));

    assertThat(candidates).hasSize(25);
    assertThat(candidates)
        .allSatisfy(
            candidate ->
                assertThat(candidate.getMetadata())
                    .containsEntry("library_id", libraries.get(0).toString()));
    assertThat(candidates)
        .extracting(Document::getScore)
        .isSortedAccordingTo((a, b) -> Double.compare(b, a));
  }

  @Test
  void aFetchKAboveTheDefaultEfSearchIsDeliveredInFull() {
    List<Document> candidates = onlyList(search(new HashSet<>(libraries), 100));

    assertThat(candidates).hasSize(100);
    assertThat(candidates)
        .extracting(Document::getScore)
        .isSortedAccordingTo((a, b) -> Double.compare(b, a));
  }

  @Test
  void aShortListIsNamedInTheExplanationProtocol() {
    StageOutcome small = search(Set.of(smallLibrary), 25);
    StageOutcome full = search(Set.of(libraries.get(0)), 25);

    // An approximate index need not reach every one of a handful of chunks among thousands; the
    // contract is that the protocol states exactly how many came back.
    List<Document> smallList = onlyList(small);
    assertThat(smallList).isNotEmpty().hasSizeLessThanOrEqualTo(SMALL_LIBRARY_CHUNKS);
    assertThat(small.explanation().notes())
        .anyMatch(
            note ->
                note.startsWith(
                    "vector search · sub-query 1 returned "
                        + smallList.size()
                        + " of fetch-k 25 candidate(s)"));
    assertThat(full.explanation().notes()).noneMatch(note -> note.contains(" of fetch-k "));
  }

  private StageOutcome search(Set<UUID> scope, int fetchK) {
    RetrievalContext context =
        new RetrievalContext(
            "Frage",
            List.of(),
            scope,
            MetadataFilter.NONE,
            new QueryProperties(8, fetchK, 1.0, 0.3, false, 3, 2, false, 50, 20, 2),
            RerankAvailability.SWITCHED_OFF);
    RetrievalState scoped = searchScopeStage.apply(context, RetrievalState.initial()).state();
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
              long start = System.nanoTime();
              StageOutcome outcome = vectorSearchStage.apply(context, scoped);
              System.out.printf(
                  "vector search over %d library(ies), fetch-k %d: %d candidate(s) in %.1f ms%n",
                  scope.size(),
                  fetchK,
                  onlyList(outcome).size(),
                  (System.nanoTime() - start) / 1e6);
              return outcome;
            });
  }

  private static List<Document> onlyList(StageOutcome outcome) {
    List<CandidateList> lists = outcome.state().candidateLists();
    assertThat(lists).hasSize(1);
    return lists.get(0).documents();
  }

  /**
   * {@code count} chunks of {@code library}, each the query vector of {@code FakeEmbeddingModel}
   * ({@code sin(0.01 i)}) plus uniform noise of a per-chunk amplitude, so the distances to the
   * query spread and stay well above the similarity threshold.
   */
  private void insertChunks(UUID library, int count) {
    jdbcTemplate.update(
        """
        INSERT INTO vector_store (id, content, metadata, embedding)
        SELECT gen_random_uuid(),
               'probe chunk ' || g,
               json_build_object('library_id', ?, 'probe_marker', ?,
                                 'document_id', gen_random_uuid()::text,
                                 'file_name', 'probe-' || g || '.md'),
               (SELECT array_agg(sin(i * 0.01) + (random() * 2 - 1) * (0.3 + 0.9 * a.amp)
                                 ORDER BY i)
                  FROM generate_series(0, 1535) i)::vector
          FROM generate_series(1, ?) g
          CROSS JOIN LATERAL (SELECT random() + g * 0 AS amp) a
        """,
        library.toString(),
        marker,
        count);
  }
}
