package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.eval.EvaluationReport.DatasetNotes;
import io.opaa.eval.EvaluationReport.OneChunkInvariantResult;
import io.opaa.eval.EvaluationReport.RunConfiguration;
import io.opaa.eval.EvaluationReport.WorstQuery;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.JobStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Retrieval-quality evaluation harness (issue #227). Indexes the frozen `eval/corpus/`
 * comic-characters corpus through the production pipeline ({@link
 * io.opaa.indexing.FileProcessingService} / {@link io.opaa.indexing.ChunkingService}), then runs
 * every case from {@code eval/golden/comic-characters.json} directly against {@link
 * VectorStore#similaritySearch}. No LLM, no {@code QueryService} — retrieval-only, per ADR-0011
 * decision 3.
 *
 * <p>Also carries out the Ein-Chunk-Invariante check ADR-0010 assigns to this harness: the
 * generator's own byte-size guard is only a cheap approximation, this is the proof.
 *
 * <p>Deliberately not part of {@code ./gradlew build}/{@code test} — see the {@code evalTest}
 * source set and {@code evaluateRetrieval} task in {@code build.gradle.kts}. Run explicitly with
 * {@code ./gradlew evaluateRetrieval}; needs Docker and pulls the {@code nomic-embed-text} model
 * into the Ollama Testcontainer on first run.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RetrievalEvaluationHarnessTest {

  private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluationHarnessTest.class);

  // Pinned per ADR-0011 decision 4: a fixed model/image keeps the baseline stable across time,
  // independent of what an "ollama/ollama:latest" pull would resolve to on a given day.
  private static final String OLLAMA_IMAGE = "ollama/ollama:0.6.5";
  private static final String EMBEDDING_MODEL = "nomic-embed-text";
  private static final int EMBEDDING_DIMENSIONS = 768;

  // Matches the production default (opaa.indexing.chunk-size / OPAA_INDEXING_CHUNK_SIZE), because
  // the whole point of this harness is to measure the pipeline as configured in production.
  private static final int CHUNK_SIZE = 1000;
  private static final int SEARCH_TOP_K = 10;

  // The production query-time default (opaa.query.similarity-threshold); reported for context but
  // deliberately NOT applied to the searches below — see the "similarityThresholdNote" in the
  // report. Ranking metrics need the full, unfiltered top-k order.
  private static final double PRODUCTION_SIMILARITY_THRESHOLD = 0.3;

  private static final String PGVECTOR_INDEX_TYPE = "hnsw";

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  @Container
  static OllamaContainer ollama =
      new OllamaContainer(DockerImageName.parse(OLLAMA_IMAGE))
          // Testcontainers' OllamaContainer auto-requests a GPU (device request, all GPUs)
          // whenever the Docker daemon merely *lists* an "nvidia" runtime — regardless of the
          // configured default runtime and regardless of whether that runtime actually works
          // (`docker info` | Runtimes). On a host with a broken WSL2/GPU passthrough this device
          // request alone makes container creation fail outright. The harness has no GPU
          // requirement — nomic-embed-text embeds on CPU — so the auto-added device request is
          // cleared again here; this createContainerCmdModifier runs after the container's own
          // and therefore wins.
          .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withDeviceRequests(List.of()));

  @org.junit.jupiter.api.io.TempDir static Path corpusWorkingDir;

  @BeforeAll
  static void pullEmbeddingModel() throws IOException, InterruptedException {
    log.info(
        "Pulling {} into the Ollama Testcontainer (first run only, ~275 MB)...", EMBEDDING_MODEL);
    var pullResult = ollama.execInContainer("ollama", "pull", EMBEDDING_MODEL);
    if (pullResult.getExitCode() != 0) {
      throw new IllegalStateException(
          "Failed to pull '"
              + EMBEDDING_MODEL
              + "' in the Ollama container: "
              + pullResult.getStderr());
    }
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.ai.ollama.base-url", ollama::getEndpoint);
    registry.add("spring.ai.model.embedding", () -> "ollama");
    registry.add("spring.ai.ollama.embedding.model", () -> EMBEDDING_MODEL);
    registry.add("spring.ai.vectorstore.pgvector.dimensions", () -> EMBEDDING_DIMENSIONS);
    registry.add("opaa.indexing.document-path", () -> corpusWorkingDir.toAbsolutePath().toString());
    registry.add("opaa.indexing.chunk-size", () -> CHUNK_SIZE);
    registry.add("opaa.indexing.batch-size", () -> 50);
    registry.add("opaa.indexing.retry-attempts", () -> 1);
    // Wider pool than the production default (2/4): this harness indexes ~1.450 documents in one
    // go and is a manually-invoked batch tool, not a live server sharing resources with requests.
    registry.add("opaa.indexing.thread-pool.core-size", () -> 8);
    registry.add("opaa.indexing.thread-pool.max-size", () -> 8);
    registry.add("opaa.indexing.thread-pool.queue-capacity", () -> 2000);
  }

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private VectorStore vectorStore;

  @Test
  void evaluatesRetrievalQualityAgainstTheGoldenDataset() throws Exception {
    Instant runStart = Instant.now();

    Path evalDir = RepoPaths.evalDir();
    Path corpusDir = evalDir.resolve("corpus").resolve("comic-characters");
    Path manifestFile = corpusDir.resolve("MANIFEST.sha256");
    Path goldenFile = evalDir.resolve("golden").resolve("comic-characters.json");

    // 1. Manifest verification — abort loudly on any manipulated byte (ADR-0011, decision 1 and 6;
    //    issue #227 acceptance criteria).
    CorpusManifest.VerificationResult manifest = CorpusManifest.verify(corpusDir, manifestFile);
    assertThat(manifest.violations())
        .as("corpus files must match MANIFEST.sha256 exactly — found: %s", manifest.violations())
        .isEmpty();
    log.info("Manifest verified: {} corpus documents", manifest.fileNames().size());

    // 2. Stage only the manifest-listed files (excludes SOURCE.md, which is not a corpus entity)
    //    and index them through the production pipeline — no shortcut.
    for (String fileName : manifest.fileNames()) {
      Files.copy(
          corpusDir.resolve(fileName),
          corpusWorkingDir.resolve(fileName),
          StandardCopyOption.REPLACE_EXISTING);
    }

    IndexingJob job = documentIndexingService.triggerIndexing();
    awaitJobCompletion(job);
    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsFailed())
        .as("no corpus document should fail to index: %s", completedJob.getErrorMessage())
        .isZero();
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(manifest.fileNames().size());
    log.info("Indexed {} documents", completedJob.getDocumentsProcessed());

    // 3. Ein-Chunk-Invariante (ADR-0010): the real, production-configured TokenTextSplitter just
    //    ran. Verify every document produced exactly one chunk — the beweiskräftige check the
    //    generator's own byte-size guard cannot provide.
    List<Document> documents = documentRepository.findAll();
    List<OneChunkInvariantResult.Violation> chunkViolations =
        documents.stream()
            .filter(d -> d.getChunkCount() != 1)
            .map(d -> new OneChunkInvariantResult.Violation(d.getFileName(), d.getChunkCount()))
            .sorted(Comparator.comparing(OneChunkInvariantResult.Violation::fileName))
            .toList();
    OneChunkInvariantResult invariantResult =
        new OneChunkInvariantResult(documents.size(), chunkViolations);
    assertThat(chunkViolations)
        .as(
            "every corpus document must produce exactly one chunk (ADR-0010) — violated by: %s",
            chunkViolations)
        .isEmpty();
    log.info("Ein-Chunk-Invariante holds for all {} documents", documents.size());

    // 4. Run every golden query directly against the vector store — retrieval only, no LLM.
    List<GoldenCase> goldenCases = GoldenDataset.load(goldenFile);
    List<RetrievalMetrics.QueryResult> results = new ArrayList<>(goldenCases.size());
    for (GoldenCase goldenCase : goldenCases) {
      List<org.springframework.ai.document.Document> hits =
          vectorStore.similaritySearch(
              SearchRequest.builder()
                  .query(goldenCase.query())
                  .topK(SEARCH_TOP_K)
                  .similarityThreshold(0.0)
                  .build());
      results.add(RetrievalMetrics.evaluate(goldenCase, dedupeByFileName(hits)));
    }
    log.info("Evaluated {} golden queries", results.size());

    // 5. Aggregate and write the report.
    MetricsAggregate overall = MetricsAggregate.of(results);
    var byCategory = MetricsAggregate.groupBy(results, GoldenCase::category);
    var byDifficulty = MetricsAggregate.groupBy(results, GoldenCase::difficulty);
    var byLanguage = MetricsAggregate.groupBy(results, GoldenCase::language);

    List<WorstQuery> worstQueries =
        results.stream()
            .sorted(
                Comparator.comparingDouble(RetrievalMetrics.QueryResult::ndcgAt10)
                    .thenComparingDouble(RetrievalMetrics.QueryResult::hitRateAt5))
            .limit(10)
            .map(
                r ->
                    new WorstQuery(
                        r.goldenCase().id(),
                        r.goldenCase().query(),
                        r.goldenCase().category(),
                        r.goldenCase().difficulty(),
                        r.goldenCase().language(),
                        r.ndcgAt10(),
                        r.hitRateAt5(),
                        r.reciprocalRank(),
                        r.recallAt10(),
                        r.goldenCase().expectedDocuments(),
                        r.rankedFileNames()))
            .toList();

    long distinctExpectedSets =
        goldenCases.stream().map(gc -> new TreeSet<>(gc.expectedDocuments())).distinct().count();
    DatasetNotes datasetNotes =
        new DatasetNotes(
            goldenCases.size(),
            (int) distinctExpectedSets,
            "Mehrere Fälle teilen sich dieselbe Erwartungsmenge, und jede crosslingual-Anfrage ist "
                + "konstruktionsbedingt der deutsche Zwilling einer englischen Anfrage mit identischer "
                + "Erwartungsmenge (siehe eval/generator/generate_golden_dataset.py). Die Fallzahl "
                + "überschätzt deshalb die Zahl unabhängiger Beobachtungen.");

    RunConfiguration runConfiguration =
        new RunConfiguration(
            "ollama",
            EMBEDDING_MODEL,
            OLLAMA_IMAGE,
            EMBEDDING_DIMENSIONS,
            CHUNK_SIZE,
            SEARCH_TOP_K,
            PRODUCTION_SIMILARITY_THRESHOLD,
            "similarityThreshold=0.0 was used for every search in this run, not the production "
                + "default above — ranking metrics need the full, unfiltered top-k order; production "
                + "queries do apply the threshold.",
            PGVECTOR_INDEX_TYPE,
            CorpusManifest.sha256Hex(manifestFile),
            manifest.fileNames().size(),
            "eval/golden/comic-characters.json",
            GoldenDataset.sha256(goldenFile),
            goldenCases.size(),
            runStart.toString(),
            Duration.between(runStart, Instant.now()).toMillis() / 1000.0);

    EvaluationReport report =
        new EvaluationReport(
            runConfiguration,
            invariantResult,
            datasetNotes,
            overall,
            byCategory,
            byDifficulty,
            byLanguage,
            worstQueries);

    Path reportFile = Path.of("build", "eval-reports", "retrieval-metrics.json");
    ReportWriter.writeJson(report, reportFile);
    String summary = ReportWriter.renderSummary(report);
    log.info(summary);
    System.out.println(summary);
    System.out.println("Report written to " + reportFile.toAbsolutePath());
  }

  private static List<String> dedupeByFileName(
      List<org.springframework.ai.document.Document> hits) {
    Set<String> seen = new LinkedHashSet<>();
    for (var hit : hits) {
      Object fileName = hit.getMetadata().get("file_name");
      if (fileName != null) {
        seen.add(fileName.toString());
      }
    }
    return List.copyOf(seen);
  }

  private void awaitJobCompletion(IndexingJob job) {
    await()
        .atMost(30, TimeUnit.MINUTES)
        .pollInterval(2, TimeUnit.SECONDS)
        .until(
            () -> {
              var latestJob = indexingJobRepository.findById(job.getId()).orElseThrow();
              return latestJob.getStatus() != JobStatus.RUNNING;
            });
  }
}
