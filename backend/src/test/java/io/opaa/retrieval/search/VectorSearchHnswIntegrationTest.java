package io.opaa.retrieval.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgvector.PGvector;
import io.opaa.metadata.MetadataFilter;
import io.opaa.retrieval.CandidateList;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RerankAvailability;
import io.opaa.retrieval.RetrievalContext;
import io.opaa.retrieval.RetrievalState;
import io.opaa.retrieval.StageOutcome;
import io.opaa.retrieval.VectorIndexScanProperties;
import io.opaa.retrieval.scope.SearchScopeStage;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

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
 *
 * <p>Other classes leave chunks embedded by {@code FakeEmbeddingModel} ({@code sin(0.01 i)})
 * behind. This class therefore searches with its own query vector, the negation of that one, so
 * those rows lie at the far end of the index and cannot crowd out its own chunks.
 */
@OpaaIntegrationTest
class VectorSearchHnswIntegrationTest {

  private static final String INDEX_NAME = "vector_store_hnsw_probe_idx";
  private static final int DIMENSIONS = 1536;
  private static final int LIBRARIES = 20;
  private static final int CHUNKS_PER_LIBRARY = 100;
  private static final int SMALL_LIBRARY_CHUNKS = 10;
  private static final int OFFSET_DIRECTIONS = 8;

  @Autowired private SearchScopeStage searchScopeStage;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ObjectMapper objectMapper;

  @Value("${opaa.database.schema}")
  private String schemaName;

  private final List<UUID> libraries = new ArrayList<>();
  private final UUID smallLibrary = UUID.randomUUID();
  private final String marker = UUID.randomUUID().toString();
  private final Random random = new Random(42);
  private VectorSearchStage vectorSearchStage;

  @BeforeEach
  void chunksAndAnHnswIndexExist() {
    vectorSearchStage =
        new VectorSearchStage(
            new VectorChunkSearch(
                jdbcTemplate,
                transactionManager,
                new NegatedFakeEmbeddingModel(),
                objectMapper,
                new VectorIndexScanProperties(20000),
                PgDistanceType.COSINE_DISTANCE,
                schemaName,
                "vector_store"));
    for (int i = 0; i < LIBRARIES; i++) {
      UUID library = UUID.randomUUID();
      libraries.add(library);
      insertChunks(library, CHUNKS_PER_LIBRARY);
    }
    insertChunks(smallLibrary, SMALL_LIBRARY_CHUNKS);
    // What neighbouring classes may leave behind: FakeEmbeddingModel's vector, outside any scope.
    jdbcTemplate.update(
        """
        INSERT INTO vector_store (id, content, metadata, embedding)
        SELECT gen_random_uuid(), 'neighbour chunk ' || g,
               json_build_object('library_id', ?, 'probe_marker', ?),
               (SELECT array_agg(sin(i * 0.01) ORDER BY i) FROM generate_series(0, 1535) i)::vector
          FROM generate_series(1, 1000) g
        """,
        UUID.randomUUID().toString(),
        marker);
    // Cosine distance 2 to the query: inside the scope, but far beyond the similarity threshold.
    jdbcTemplate.update(
        """
        INSERT INTO vector_store (id, content, metadata, embedding)
        SELECT gen_random_uuid(), 'opposite chunk',
               json_build_object('library_id', ?, 'probe_marker', ?,
                                 'document_id', gen_random_uuid()::text,
                                 'file_name', 'probe-opposite.md'),
               (SELECT array_agg(sin(i * 0.01) ORDER BY i) FROM generate_series(0, 1535) i)::vector
        """,
        smallLibrary.toString(),
        marker);
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

  /** The opposite chunk is in scope and within fetch-k, so only the threshold keeps it out. */
  @Test
  void aShortListIsNamedInTheExplanationProtocolAndHonoursTheThreshold() {
    StageOutcome small = search(Set.of(smallLibrary), 25);
    StageOutcome full = search(Set.of(libraries.get(0)), 25);

    assertThat(onlyList(small))
        .hasSize(SMALL_LIBRARY_CHUNKS)
        .extracting(Document::getText)
        .doesNotContain("opposite chunk");
    assertThat(small.explanation().notes())
        .anyMatch(note -> note.startsWith("vector search · sub-query 1 returned 10 of fetch-k 25"));
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
   * {@code count} chunks of {@code library}, each the query vector ({@code -sin(0.01 i)}) plus an
   * offset of a per-chunk amplitude within the {@value #OFFSET_DIRECTIONS}-dimensional span of
   * {@code cos(0.003 k i + k)}, so the distances to the query spread and stay well below the
   * threshold's distance (at most about 0.5 against 0.7).
   *
   * <p>The offsets span few dimensions, as real embeddings do. Independent noise in all 1536
   * dimensions makes the HNSW build prune the in-edges of early-inserted chunks until roughly a
   * quarter of them, and at times three quarters of the first library, are unreachable from the
   * entry point: then not even an exhaustive iterative scan finds fetch-k of them.
   */
  private void insertChunks(UUID library, int count) {
    List<Object[]> rows = new ArrayList<>(count);
    for (int g = 1; g <= count; g++) {
      double scale = (0.3 + 0.9 * random.nextDouble()) / 3;
      double[] weights = new double[OFFSET_DIRECTIONS];
      for (int k = 0; k < OFFSET_DIRECTIONS; k++) {
        weights[k] = random.nextDouble() * 2 - 1;
      }
      float[] embedding = new float[DIMENSIONS];
      for (int i = 0; i < DIMENSIONS; i++) {
        double offset = 0;
        for (int k = 1; k <= OFFSET_DIRECTIONS; k++) {
          offset += weights[k - 1] * Math.cos(0.003 * k * i + k);
        }
        embedding[i] = (float) (-Math.sin(i * 0.01) + scale * offset);
      }
      rows.add(
          new Object[] {
            "probe chunk " + g,
            library.toString(),
            marker,
            "probe-" + g + ".md",
            new PGvector(embedding)
          });
    }
    jdbcTemplate.batchUpdate(
        """
        INSERT INTO vector_store (id, content, metadata, embedding)
        VALUES (gen_random_uuid(), ?,
                json_build_object('library_id', ?, 'probe_marker', ?,
                                  'document_id', gen_random_uuid()::text, 'file_name', ?),
                ?)
        """,
        rows);
  }

  /** Embeds every text as {@code -sin(0.01 i)}, the opposite of {@code FakeEmbeddingModel}. */
  private static final class NegatedFakeEmbeddingModel implements EmbeddingModel {

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
      List<Embedding> embeddings = new ArrayList<>();
      for (int i = 0; i < request.getInstructions().size(); i++) {
        embeddings.add(new Embedding(vector(), i));
      }
      return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
      return vector();
    }

    private static float[] vector() {
      float[] embedding = new float[DIMENSIONS];
      for (int i = 0; i < DIMENSIONS; i++) {
        embedding[i] = (float) -Math.sin(i * 0.01);
      }
      return embedding;
    }
  }
}
