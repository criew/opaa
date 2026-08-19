package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import io.opaa.auth.SystemRole;
import io.opaa.eval.EvaluationReport.DatasetNotes;
import io.opaa.eval.EvaluationReport.OneChunkInvariantResult;
import io.opaa.eval.EvaluationReport.RunConfiguration;
import io.opaa.eval.EvaluationReport.WorstQuery;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.JobStatus;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryVisibility;
import io.opaa.organization.Organization;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

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
 * <p>The measurement contract this harness implements (gain function, IDCG basis, k-windows,
 * threshold handling, micro- vs. macro-averaging) is pinned in ADR-0012, versioned via {@link
 * EvaluationReport#CURRENT_MEASUREMENT_CONTRACT_VERSION}.
 *
 * <p>Deliberately not part of {@code ./gradlew build}/{@code test} — see the {@code evalTest}
 * source set and {@code evaluateRetrieval} task in {@code build.gradle.kts}. Run explicitly with
 * {@code ./gradlew evaluateRetrieval}; needs Docker and pulls the {@code nomic-embed-text} model
 * into the Ollama Testcontainer on first run.
 */
// AuthProfileGuard (ADR-0005) refuses to start the context without an auth profile, so the harness
// declares one just like every other @SpringBootTest in this repository. Without it the run aborts
// during context startup and never reaches a single measurement.
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class RetrievalEvaluationHarnessTest {

  private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluationHarnessTest.class);

  // Pinned per ADR-0011 decision 4: a fixed *tag* (not "nomic-embed-text", which resolves to
  // ":latest") keeps the baseline stable across time. The digest assertion below is the second,
  // stronger layer: even a tag pin does not stop the tag itself from being force-pushed upstream,
  // so we also pin and verify the content digest Ollama reports for the pulled model.
  private static final String OLLAMA_IMAGE = "ollama/ollama:0.6.5";
  private static final String EMBEDDING_MODEL = "nomic-embed-text:v1.5";
  private static final int EMBEDDING_DIMENSIONS = 768;

  // Captured with `ollama pull nomic-embed-text:v1.5` against a freshly started
  // ollama/ollama:0.6.5 container and read back from GET /api/tags (the "digest" field), on
  // 2026-08-03. If a future pull ever reports a different digest for this exact tag, that is model
  // drift, not a harness bug — see the assertion in pullEmbeddingModel() and ADR-0011 decision 4.
  private static final String EXPECTED_EMBEDDING_MODEL_DIGEST =
      "0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f";

  // System property to opt out of the CPU-only pin below, for local experiments that want to
  // compare GPU-embedded vectors. Not used by evaluateRetrieval as invoked from CI/README —
  // CPU and GPU embedding kernels are not guaranteed to be bit-identical, so a GPU run would not
  // be comparable to the pinned CI/baseline numbers.
  private static final String ALLOW_GPU_PROPERTY = "opaa.eval.allowGpu";

  // Named Docker volume (not an anonymous/ephemeral one) so the pulled ~275 MB model survives
  // across separate `./gradlew evaluateRetrieval` invocations on the same machine instead of being
  // re-pulled every time — see the eval/README.md "Modell-Cache" section for what this does and
  // does not guarantee (ephemeral CI runners still pull once per run).
  private static final String OLLAMA_MODEL_VOLUME = "opaa-eval-ollama-models";
  private static final String OLLAMA_MODEL_VOLUME_PATH = "/root/.ollama";

  private static volatile String actualEmbeddingModelDigest;

  private static final int SEARCH_TOP_K = 10;

  // The production query-time default (opaa.query.similarity-threshold); reported for context but
  // deliberately NOT applied to the searches below — see the "similarityThresholdNote" in the
  // report. Ranking metrics need the full, unfiltered top-k order.
  private static final double PRODUCTION_SIMILARITY_THRESHOLD = 0.3;

  private static final String PGVECTOR_INDEX_TYPE = "hnsw";

  // Expected value of opaa.indexing.chunk-size as configured by application.yml's own default
  // (OPAA_INDEXING_CHUNK_SIZE, currently 1000). Deliberately NOT registered as a
  // @DynamicPropertySource override (see ADR-0010): the harness must measure whatever chunk size
  // production is actually configured with, not a value frozen here. This constant exists solely
  // so a change to that default fails the assertion below loudly, instead of the harness silently
  // reporting a chunk size the corpus's Ein-Chunk-Invariante was never re-verified against.
  private static final int EXPECTED_APPLICATION_DEFAULT_CHUNK_SIZE = 1000;

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
          // and therefore wins. Forcing CPU is also deliberately good for baseline stability, not
          // just a workaround: CPU and GPU embedding kernels are not guaranteed to be
          // bit-identical,
          // so a GPU-embedded run would not be comparable across machines or to CI. Opt out for
          // local experiments with -Dopaa.eval.allowGpu=true.
          .withCreateContainerCmdModifier(
              cmd -> {
                boolean allowGpu = Boolean.getBoolean(ALLOW_GPU_PROPERTY);
                if (!allowGpu) {
                  cmd.getHostConfig().withDeviceRequests(List.of());
                  // Defensive: prove the clear above actually took effect, instead of trusting
                  // modifier-ordering silently. A future Testcontainers upgrade that changes when
                  // OllamaContainer's own GPU-adding modifier runs relative to this one would
                  // otherwise reintroduce a GPU device request without any visible failure.
                  var deviceRequests = cmd.getHostConfig().getDeviceRequests();
                  if (deviceRequests != null && !deviceRequests.isEmpty()) {
                    throw new IllegalStateException(
                        "Expected no GPU device requests on the Ollama container ("
                            + ALLOW_GPU_PROPERTY
                            + "=false), but found: "
                            + deviceRequests
                            + ". A Testcontainers upgrade likely reordered "
                            + "createContainerCmdModifier calls.");
                  }
                }
              })
          // Named volume for the model cache — see OLLAMA_MODEL_VOLUME javadoc above.
          .withCreateContainerCmdModifier(
              cmd -> {
                var existingBinds = cmd.getHostConfig().getBinds();
                var binds = new ArrayList<Bind>();
                if (existingBinds != null) {
                  binds.addAll(List.of(existingBinds));
                }
                binds.add(new Bind(OLLAMA_MODEL_VOLUME, new Volume(OLLAMA_MODEL_VOLUME_PATH)));
                cmd.getHostConfig().withBinds(binds.toArray(new Bind[0]));
              });

  @org.junit.jupiter.api.io.TempDir static Path corpusWorkingDir;

  @BeforeAll
  static void pullEmbeddingModel() throws IOException, InterruptedException {
    // Check the (local, in-container) /api/tags endpoint before pulling anything. If the model is
    // already present with exactly the expected digest — the common case on a warm
    // OLLAMA_MODEL_VOLUME, whether on a developer machine or restored from the CI cache (see
    // .github/workflows/retrieval-regression.yml) — skip 'ollama pull' entirely. Without this
    // check, 'ollama pull' always reaches out to the model registry to resolve the tag's manifest
    // even when every layer is already cached locally, which contradicted this harness's claim
    // (eval/README.md, ADR-0011) of not needing to download the embedding model again on a warm
    // cache (PR #301 review, Befund 5). Narrowly scoped claim, not "no third-party network access
    // at all": the pgvector/pgvector and ollama/ollama base images are still pulled from Docker
    // Hub regardless of this check (Testcontainers itself does that, independent of the model). GET
    // /api/tags itself never leaves the Docker network either way — it talks to the Ollama
    // container this test just started, not to any third party.
    String cachedDigest = tryFetchEmbeddingModelDigest();
    if (cachedDigest != null && EXPECTED_EMBEDDING_MODEL_DIGEST.equalsIgnoreCase(cachedDigest)) {
      log.info(
          "{} already present in the Ollama Testcontainer with the expected digest {} — skipping "
              + "'ollama pull' (no third-party network access needed).",
          EMBEDDING_MODEL,
          cachedDigest);
      actualEmbeddingModelDigest = cachedDigest;
      return;
    }

    log.info(
        "Pulling {} into the Ollama Testcontainer (cached in the '{}' Docker volume after the "
            + "first run)...",
        EMBEDDING_MODEL,
        OLLAMA_MODEL_VOLUME);
    var pullResult = ollama.execInContainer("ollama", "pull", EMBEDDING_MODEL);
    if (pullResult.getExitCode() != 0) {
      throw new IllegalStateException(
          "Failed to pull '"
              + EMBEDDING_MODEL
              + "' in the Ollama container: "
              + pullResult.getStderr());
    }
    actualEmbeddingModelDigest = fetchEmbeddingModelDigest();
    if (!EXPECTED_EMBEDDING_MODEL_DIGEST.equalsIgnoreCase(actualEmbeddingModelDigest)) {
      throw new IllegalStateException(
          "Embedding model drift detected: '"
              + EMBEDDING_MODEL
              + "' now resolves to digest "
              + actualEmbeddingModelDigest
              + ", but this harness pins "
              + EXPECTED_EMBEDDING_MODEL_DIGEST
              + " (see EXPECTED_EMBEDDING_MODEL_DIGEST javadoc). The tag was force-updated "
              + "upstream — treat this as a deliberate baseline re-pin (new digest constant, new "
              + "evaluateRetrieval run, updated numbers in the PR), not a code bug.");
    }
    log.info("Embedding model digest verified: {}", actualEmbeddingModelDigest);
  }

  /**
   * Like {@link #fetchEmbeddingModelDigest()}, but {@code null} instead of throwing when the tag is
   * not present in the container yet (fresh/empty volume) — the expected case on the very first
   * run.
   */
  private static String tryFetchEmbeddingModelDigest() throws IOException, InterruptedException {
    try {
      return fetchEmbeddingModelDigest();
    } catch (IllegalStateException e) {
      return null;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OllamaTagsResponse(List<OllamaModelTag> models) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OllamaModelTag(String name, String digest) {}

  private static String fetchEmbeddingModelDigest() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(ollama.getEndpoint() + "/api/tags")).GET().build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "GET /api/tags on the Ollama container failed with status " + response.statusCode());
    }
    OllamaTagsResponse tags =
        JsonMapper.builder().build().readValue(response.body(), OllamaTagsResponse.class);
    return tags.models().stream()
        .filter(m -> EMBEDDING_MODEL.equals(m.name()))
        .map(OllamaModelTag::digest)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Model '" + EMBEDDING_MODEL + "' not found in /api/tags response: " + tags));
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
    // Deliberately NOT overriding opaa.indexing.chunk-size here — see
    // EXPECTED_APPLICATION_DEFAULT_CHUNK_SIZE javadoc and ADR-0010: the harness measures whatever
    // chunk-size production is actually configured with (application.yml's own default).
    registry.add("opaa.indexing.batch-size", () -> 50);
    registry.add("opaa.indexing.retry-attempts", () -> 1);
    // Single-threaded on purpose (unlike the production default of core=2/max=4): with more than
    // one worker thread, the order in which chunks are inserted into pgvector — and therefore the
    // shape of the HNSW graph the approximate search walks — becomes nondeterministic across runs.
    // Ollama also serializes embedding calls internally on CPU (~650ms sequential per call
    // observed), so extra harness-side concurrency buys little throughput anyway; determinism
    // matters more here than raw indexing speed for a manually-invoked, non-interactive batch tool.
    registry.add("opaa.indexing.thread-pool.core-size", () -> 1);
    registry.add("opaa.indexing.thread-pool.max-size", () -> 1);
    registry.add("opaa.indexing.thread-pool.queue-capacity", () -> 2000);
  }

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private VectorStore vectorStore;
  @Autowired private IndexingProperties indexingProperties;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  // #419: triggerIndexing needs a caller-chosen target library and an authorized caller -
  // set up once per run, not pinned to a well-known system library id, since #419 already stopped
  // production indexing from targeting one by default and #521 later deleted it outright. The
  // measurements themselves are unaffected: this harness reads via vectorStore.similaritySearch
  // (see the class Javadoc), not through the permission-aware query path, so which library the
  // corpus lands in does not change what is measured.
  private UUID evalUserId;
  private UUID evalLibraryId;

  @BeforeEach
  void setUpIndexingTarget() {
    jdbcTemplate.update("DELETE FROM users WHERE email = 'eval-harness@example.com'");
    evalUserId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'eval-issuer', 'eval-harness@example.com',"
            + " 'Eval Harness User', now(), ?, ?)",
        evalUserId,
        "eval-harness-" + evalUserId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);

    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Eval-Zielbibliothek",
                null,
                evalUserId,
                LibraryVisibility.PRIVATE,
                false,
                false));
    evalLibraryId = library.getId();

    // KnowledgeLibraryRepository#save alone does not grant an AssetGrant (only
    // KnowledgeLibraryService#createLibrary does that) - and DocumentIndexingService never bypasses
    // the EDITOR check for a system admin (PR #431 review, Befund 2; the one exception that used to
    // exist for the well-known system library is gone too, #521). Grant OWNER explicitly, same as
    // any real library creation.
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?,"
            + " 'OWNER', now(), now())",
        UUID.randomUUID(),
        evalLibraryId,
        Organization.DEFAULT_ID,
        evalUserId);
  }

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

    // Read the chunk size the running application context is actually configured with — see
    // EXPECTED_APPLICATION_DEFAULT_CHUNK_SIZE javadoc. A change to opaa.indexing.chunk-size shows
    // up here as a failed assertion, not a silently stale measurement.
    int actualChunkSize = indexingProperties.chunkSize();
    assertThat(actualChunkSize)
        .as(
            "opaa.indexing.chunk-size changed from the value this harness (and the corpus's "
                + "Ein-Chunk-Invariante, see ADR-0010) assumes. This is not a harness bug: "
                + "re-verify the invariant, re-run evaluateRetrieval, and update "
                + "EXPECTED_APPLICATION_DEFAULT_CHUNK_SIZE deliberately.")
        .isEqualTo(EXPECTED_APPLICATION_DEFAULT_CHUNK_SIZE);

    IndexingJob job = documentIndexingService.triggerIndexing(evalLibraryId, evalUserId, true);
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

    Comparator<RetrievalMetrics.QueryResult> worstFirst =
        Comparator.comparingDouble(RetrievalMetrics.QueryResult::ndcgAt10)
            .thenComparingDouble(RetrievalMetrics.QueryResult::hitRateAt5);

    List<WorstQuery> allQueryResults =
        results.stream()
            .sorted(worstFirst)
            .map(RetrievalEvaluationHarnessTest::toWorstQuery)
            .toList();
    List<WorstQuery> worstQueries = allQueryResults.stream().limit(10).toList();

    long distinctExpectedSets =
        goldenCases.stream().map(gc -> new TreeSet<>(gc.expectedDocuments())).distinct().count();
    DatasetNotes datasetNotes =
        new DatasetNotes(
            goldenCases.size(),
            (int) distinctExpectedSets,
            "Mehrere Fälle teilen sich dieselbe Erwartungsmenge, und jede crosslingual-Anfrage ist "
                + "konstruktionsbedingt der deutsche Zwilling einer englischen Anfrage mit identischer "
                + "Erwartungsmenge (siehe eval/generator/generate_golden_dataset.py). Die Fallzahl "
                + "überschätzt deshalb die Zahl unabhängiger Beobachtungen. Der Sprachvergleich "
                + "(de vs. en) ist zusätzlich mit dem Anteil an 'hard'-Fällen konfundiert — siehe "
                + "eval/README.md.");

    RunConfiguration runConfiguration =
        new RunConfiguration(
            "ollama",
            EMBEDDING_MODEL,
            actualEmbeddingModelDigest,
            OLLAMA_IMAGE,
            EMBEDDING_DIMENSIONS,
            actualChunkSize,
            actualChunkSize == EXPECTED_APPLICATION_DEFAULT_CHUNK_SIZE,
            indexingProperties.chunkOverlap(),
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
            EvaluationReport.CURRENT_MEASUREMENT_CONTRACT_VERSION,
            runConfiguration,
            invariantResult,
            datasetNotes,
            overall,
            byCategory,
            byDifficulty,
            byLanguage,
            worstQueries,
            allQueryResults);

    Path reportFile = Path.of("build", "eval-reports", "retrieval-metrics.json");
    ReportWriter.writeJson(report, reportFile);
    String summary = ReportWriter.renderSummary(report);
    log.info(summary);
    System.out.println("Report written to " + reportFile.toAbsolutePath());
  }

  private static WorstQuery toWorstQuery(RetrievalMetrics.QueryResult r) {
    return new WorstQuery(
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
        r.rankedFileNames());
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

  /**
   * Waits for the corpus to finish indexing. The 45-minute budget is deliberate and matches the
   * runtime the CI job already documents: on a GitHub Actions runner (2 vCPU shared between the
   * Postgres, Ollama and application containers) the 1448-document corpus takes roughly 38 minutes,
   * with the sequential Ollama embedding latency dominating — see the {@code timeout-minutes}
   * comment in {@code .github/workflows/retrieval-regression.yml}. The previous 30 minutes
   * contradicted that measurement and only ever passed on a fast runner: PR #415 stalled at 1121 of
   * 1448 documents (~1.6 s/document), while the nightly run before it managed ~0.85 s/document and
   * finished with minutes to spare. The budget must also stay comfortably below the workflow's
   * {@code timeout-minutes} so a genuinely stuck indexing run fails here — with a diagnosable test
   * failure — instead of being killed as a cancelled job.
   */
  private void awaitJobCompletion(IndexingJob job) {
    await()
        .atMost(45, TimeUnit.MINUTES)
        .pollInterval(2, TimeUnit.SECONDS)
        .until(
            () -> {
              var latestJob = indexingJobRepository.findById(job.getId()).orElseThrow();
              return latestJob.getStatus() != JobStatus.RUNNING;
            });
  }
}
