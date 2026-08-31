package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.eval.EvaluationReport.ChunkCountInvariantResult;
import io.opaa.eval.EvaluationReport.DatasetNotes;
import io.opaa.eval.EvaluationReport.RunConfiguration;
import io.opaa.eval.EvaluationReport.WorstQuery;
import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.JobStatus;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.query.QueryProperties;
import io.opaa.query.QueryService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
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
 * Retrieval-quality evaluation harness for the {@code city-landmarks} domain (issue #234), a
 * second, deliberately multi-chunk domain built on top of the machinery {@link
 * RetrievalEvaluationHarnessTest} (issue #227) and issue #721 already provide — see {@link
 * EvalDomainConfig#CITY_LANDMARKS}'s Javadoc for the domain's chunk-count profile. Indexes the
 * frozen `eval/corpus/city-landmarks` corpus through the production pipeline ({@link
 * io.opaa.indexing.FileProcessingService} / {@link io.opaa.indexing.ChunkingService}), then runs
 * every case from {@code eval/golden/city-landmarks.json} directly against {@link
 * VectorStore#similaritySearch}. No LLM — retrieval-only, per ADR-0011 decision 3.
 *
 * <p>Like {@link RetrievalEvaluationHarnessTest}, it additionally runs the <b>pipeline measurement
 * path</b> (issue #1039, {@link PipelineHarnessSupport}) on the same index afterwards: the same
 * golden cases through {@code QueryService}'s production retrieval chain, at the production
 * configuration including the applied similarity threshold, reported separately at its own window.
 * The raw-vector path's numbers, report file and baseline are untouched by it.
 *
 * <p>This class is a deliberate near-duplicate of {@link RetrievalEvaluationHarnessTest} rather
 * than a parameterization of it (see issue #721 PR #723, "Umfang-Entscheidungen": a second domain
 * "reiht sich als zweite Konstante + zweiter Testlauf ein, ohne die bestehende Struktur umzubauen")
 * — this keeps the comic-characters harness, its baseline and its CI behavior completely unchanged
 * by this PR (issue #234 acceptance criterion: "Die Comichelden-Baseline bleibt unverändert
 * gültig").
 *
 * <p>Also carries out the domain's chunk-count invariant check ADR-0010 assigns to this harness
 * (see {@link ChunkCountExpectation}) — for {@code city-landmarks} that is the
 * Mehr-Chunk-Invariante (ADR-0010 Nachtrag, issue #721): the generator's own size heuristics are
 * only an approximation, this is the proof.
 *
 * <p>The measurement contract this harness implements (gain function, IDCG basis, k-windows,
 * threshold handling, micro- vs. macro-averaging, the document-bound window from {@link
 * DocumentRanking}) is pinned in ADR-0012, versioned via {@link
 * EvaluationReport#CURRENT_MEASUREMENT_CONTRACT_VERSION}.
 *
 * <p>Deliberately not part of {@code ./gradlew build}/{@code test} — see the {@code evalTest}
 * source set and {@code evaluateCityLandmarksRetrieval} task in {@code build.gradle.kts}. Run
 * explicitly with {@code ./gradlew evaluateCityLandmarksRetrieval}; needs Docker and pulls the
 * {@code nomic-embed-text} model into the Ollama Testcontainer on first run.
 */
// AuthProfileGuard (ADR-0005) refuses to start the context without an auth profile, so the harness
// declares one just like every other @SpringBootTest in this repository. Without it the run aborts
// during context startup and never reaches a single measurement.
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class CityLandmarksRetrievalEvaluationHarnessTest {

  private static final Logger log =
      LoggerFactory.getLogger(CityLandmarksRetrievalEvaluationHarnessTest.class);

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

  // Issue #234: city-landmarks' domain configuration — chunk-count expectation, document-bound
  // k-window, chunk-search sizing. See EvalDomainConfig.CITY_LANDMARKS' Javadoc for the measured
  // chunk-count distribution this configuration is based on.
  private static final EvalDomainConfig DOMAIN = EvalDomainConfig.CITY_LANDMARKS;

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

  // Not @Container-managed: issue #1076 needs to skip container creation entirely when
  // opaa.eval.ollamaBaseUrl selects an external endpoint, and TestcontainersExtension does not
  // support a conditionally-null @Container field. Started (if at all) by startOllamaIfNeeded()
  // below, before Spring's context preparation reads ollamaEndpoint() via @DynamicPropertySource —
  // preparation happens on first test-instance construction, which is always after every @BeforeAll
  // method has run.
  static OllamaContainer ollama;

  @org.junit.jupiter.api.io.TempDir static Path corpusWorkingDir;

  @BeforeAll
  static void pullEmbeddingModel() throws IOException, InterruptedException {
    startOllamaIfNeeded();

    // Check the /api/tags endpoint before pulling anything. If the model is already present with
    // exactly the expected digest — the common case on a warm OLLAMA_MODEL_VOLUME, whether on a
    // developer machine or restored from the CI cache (see
    // .github/workflows/retrieval-regression.yml) — skip 'ollama pull' entirely. Without this
    // check, 'ollama pull' always reaches out to the model registry to resolve the tag's manifest
    // even when every layer is already cached locally, which contradicted this harness's claim
    // (eval/README.md, ADR-0011) of not needing to download the embedding model again on a warm
    // cache (PR #301 review, Befund 5). Narrowly scoped claim, not "no third-party network access
    // at all": the pgvector/pgvector and ollama/ollama base images are still pulled from Docker
    // Hub regardless of this check (Testcontainers itself does that, independent of the model). GET
    // /api/tags itself never leaves the Docker network either way in the default (Testcontainer)
    // mode — it talks to the Ollama container this test just started, not to any third party.
    String cachedDigest = tryFetchEmbeddingModelDigest();
    if (cachedDigest != null && EXPECTED_EMBEDDING_MODEL_DIGEST.equalsIgnoreCase(cachedDigest)) {
      log.info(
          "{} already present at {} with the expected digest {} — skipping 'ollama pull'.",
          EMBEDDING_MODEL,
          ollamaEndpoint(),
          cachedDigest);
      actualEmbeddingModelDigest = cachedDigest;
      return;
    }

    log.info(
        "Pulling {} into the Ollama endpoint at {}"
            + (EvalOllamaEndpoint.isExternal()
                ? "..."
                : " (cached in the '"
                    + OLLAMA_MODEL_VOLUME
                    + "' Docker volume after the first "
                    + "run)..."),
        EMBEDDING_MODEL,
        ollamaEndpoint());
    if (EvalOllamaEndpoint.isExternal()) {
      pullEmbeddingModelViaHttp();
    } else {
      var pullResult = ollama.execInContainer("ollama", "pull", EMBEDDING_MODEL);
      if (pullResult.getExitCode() != 0) {
        throw new IllegalStateException(
            "Failed to pull '"
                + EMBEDDING_MODEL
                + "' in the Ollama container: "
                + pullResult.getStderr());
      }
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
   * Starts the Ollama Testcontainer, unless {@link EvalOllamaEndpoint#isExternal()} selects an
   * external endpoint instead (issue #1076) — in which case no container is created at all and
   * {@link #ollamaEndpoint()} returns that external URL for the rest of the run. Runs from {@link
   * #pullEmbeddingModel()}, itself a {@code @BeforeAll}; Spring's context preparation (which reads
   * {@link #ollamaEndpoint()} via {@link #configureProperties(DynamicPropertyRegistry)}) only
   * happens once every {@code @BeforeAll} method has completed, so this ordering is safe regardless
   * of {@code @BeforeAll} method declaration order.
   */
  private static void startOllamaIfNeeded() {
    if (EvalOllamaEndpoint.isExternal()) {
      log.warn(
          "Using external Ollama endpoint {} ({} system property) instead of a Testcontainer — "
              + "this run is NOT comparable to the pinned CI/baseline numbers (analogous to the {} "
              + "opt-out), see eval/README.md, \"Externer Ollama-Endpunkt\".",
          EvalOllamaEndpoint.externalBaseUrl(),
          EvalOllamaEndpoint.BASE_URL_PROPERTY,
          ALLOW_GPU_PROPERTY);
      return;
    }
    ollama =
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
    ollama.start();
  }

  @AfterAll
  static void stopOllamaIfStarted() {
    // Mirrors what TestcontainersExtension does automatically for @Container-managed fields — this
    // one is managed manually (see startOllamaIfNeeded()) so it needs the same explicit stop. null
    // in external-endpoint mode (issue #1076), where there is nothing this harness started.
    if (ollama != null) {
      ollama.stop();
    }
  }

  /**
   * The Ollama endpoint this run talks to — the external one if configured, else the container's.
   */
  private static String ollamaEndpoint() {
    return EvalOllamaEndpoint.isExternal()
        ? EvalOllamaEndpoint.externalBaseUrl()
        : ollama.getEndpoint();
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
        HttpRequest.newBuilder(URI.create(ollamaEndpoint() + "/api/tags")).GET().build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "GET /api/tags on the Ollama endpoint failed with status " + response.statusCode());
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

  // Non-streaming POST /api/pull only returns once the whole ~275 MB model is on disk; 10 minutes
  // comfortably covers a cold pull over a normal connection without masking a genuinely hung
  // request as a slow one indefinitely.
  private static final Duration PULL_TIMEOUT = Duration.ofMinutes(10);

  /**
   * Pulls {@link #EMBEDDING_MODEL} on an external Ollama endpoint (issue #1076) — {@code
   * ollama.execInContainer} only works against a Testcontainer, so this equivalent goes through
   * Ollama's {@code POST /api/pull} HTTP API instead, non-streaming so the call blocks until the
   * pull actually finishes (or fails).
   */
  private static void pullEmbeddingModelViaHttp() throws IOException, InterruptedException {
    HttpClient client = HttpClient.newHttpClient();
    String requestBody =
        JsonMapper.builder()
            .build()
            .writeValueAsString(Map.of("model", EMBEDDING_MODEL, "stream", false));
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(ollamaEndpoint() + "/api/pull"))
            .timeout(PULL_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (java.net.http.HttpTimeoutException e) {
      throw new IllegalStateException(
          "Timed out after "
              + PULL_TIMEOUT
              + " pulling '"
              + EMBEDDING_MODEL
              + "' via POST /api/pull on the external Ollama endpoint "
              + ollamaEndpoint()
              + " — check that the endpoint is reachable and has network access to pull the model.",
          e);
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Failed to pull '"
              + EMBEDDING_MODEL
              + "' via POST /api/pull on the external Ollama endpoint: HTTP "
              + response.statusCode()
              + " — "
              + response.body());
    }
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    // #762: no native Ollama provider anymore - reach the same container through its
    // OpenAI-compatible /v1 endpoint instead (application.yml's own default path since #762).
    registry.add("spring.ai.openai.embedding.base-url", () -> ollamaEndpoint() + "/v1");
    registry.add("spring.ai.openai.embedding.model", () -> EMBEDDING_MODEL);
    registry.add("spring.ai.vectorstore.pgvector.dimensions", () -> EMBEDDING_DIMENSIONS);
    // ADR-0018: a FILESYSTEM library reads its own sourcePath, not an application-wide path - the
    // eval library created in setUpIndexingTarget() below points sourcePath at this same
    // corpusWorkingDir, so the allowlist must cover it or
    // AsyncIndexingExecutor refuses the run outright (empty allowlist disables FILESYSTEM
    // entirely, see FilesystemPathAllowlist).
    registry.add(
        "opaa.indexing.filesystem-allowlist", () -> corpusWorkingDir.toAbsolutePath().toString());
    // Deliberately NOT overriding opaa.indexing.chunk-size here — see
    // EXPECTED_APPLICATION_DEFAULT_CHUNK_SIZE javadoc and ADR-0010: the harness measures whatever
    // chunk-size production is actually configured with (application.yml's own default).
    registry.add("opaa.indexing.batch-size", () -> 50);
    // The only opaa.query.* override: the pipeline path measures the decomposition-off variant,
    // because this context has no chat model and a failing decomposition would silently degrade to
    // single-query retrieval (ADR-0012, Nachtrag Pipeline-Messpfad, Entscheidung 15 — where the
    // open model decision is recorded). Every other query parameter stays at its production
    // default and is read from the running context.
    registry.add("opaa.query.query-decomposition-enabled", () -> false);
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
  // Issue #721: reused, not reimplemented, to build the chunk map — the same production beans
  // FileProcessingService drives (see its Javadoc), so the chunk texts the map is built from are
  // exactly what was actually indexed, not a second, potentially drifting re-implementation.
  @Autowired private DocumentService documentService;
  @Autowired private ChunkingService chunkingService;
  // #1039: the production query pipeline itself, for the second (pipeline) measurement path — the
  // very beans a real request runs through, not a re-implementation of steps 2 to 6.
  @Autowired private QueryService queryService;
  @Autowired private QueryProperties queryProperties;

  // #419: triggerIndexing needs a caller-chosen target library and an authorized caller -
  // set up once per run, not pinned to a well-known system library id, since #419 already stopped
  // production indexing from targeting one by default and #521 later deleted it outright. The
  // measurements themselves are unaffected: this harness reads via vectorStore.similaritySearch
  // (see the class Javadoc), not through the permission-aware query path, so which library the
  // corpus lands in does not change what is measured.
  //
  // #552: the library must be a FILESYSTEM library whose sourcePath is corpusWorkingDir, not the
  // no-config default DocumentSourceType.UPLOAD KnowledgeLibrary#ownedByUser's six-argument
  // overload defaults to (#478/ADR-0018 introduced per-library quellentyp after this harness's own
  // #536 library-model fix landed) - triggerIndexing() rejects an UPLOAD library with 409 before a
  // single document is indexed (DocumentIndexingService#toIndexingSourceType), which is exactly the
  // ResponseStatusException the nightly run
  // (https://github.com/criew/opaa/actions/runs/32327052407) failed with.
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
                DocumentSourceType.FILESYSTEM,
                corpusWorkingDir.toAbsolutePath().toString(),
                null,
                null,
                null,
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

    // Decided up front although the pipeline path (step 6 below) only runs at the very end: the
    // check reads nothing but the query configuration, which is fixed from context startup. Failing
    // it after the hour-long raw-vector path would cost that path its baseline verdict for nothing.
    PipelineHarnessSupport.requireMeasurableConfiguration(queryProperties);

    Path evalDir = RepoPaths.evalDir();
    Path corpusDir = evalDir.resolve("corpus").resolve(DOMAIN.name());
    Path manifestFile = corpusDir.resolve("MANIFEST.sha256");
    Path goldenFile = evalDir.resolve("golden").resolve(DOMAIN.goldenDatasetFileName());

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

    CurrentUser evalCaller =
        CurrentUser.of(
            evalUserId, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Eval Harness User");
    IndexingJob job = documentIndexingService.triggerIndexing(evalLibraryId, evalCaller);
    awaitJobCompletion(job);
    var completedJob = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completedJob.getDocumentsFailed())
        .as("no corpus document should fail to index: %s", completedJob.getErrorMessage())
        .isZero();
    assertThat(completedJob.getDocumentsProcessed()).isEqualTo(manifest.fileNames().size());
    log.info("Indexed {} documents", completedJob.getDocumentsProcessed());

    // 3. Chunk-count invariant (ADR-0010, Nachtrag #721): the real, production-configured
    //    TokenTextSplitter just ran. Verify every document satisfies the domain's declared
    //    expectation — for comic-characters that is still, unchanged, "exactly one chunk".
    List<Document> documents = documentRepository.findAll();
    List<ChunkCountExpectation.DocumentChunkCount> documentChunkCounts =
        documents.stream()
            .map(
                d ->
                    new ChunkCountExpectation.DocumentChunkCount(
                        d.getFileName(), d.getChunkCount()))
            .toList();
    List<ChunkCountExpectation.Violation> expectationViolations =
        DOMAIN.chunkCountExpectation().violations(documentChunkCounts);
    List<ChunkCountInvariantResult.Violation> chunkViolations =
        expectationViolations.stream()
            .map(v -> new ChunkCountInvariantResult.Violation(v.fileName(), v.chunkCount()))
            .sorted(Comparator.comparing(ChunkCountInvariantResult.Violation::fileName))
            .toList();
    ChunkCountInvariantResult invariantResult =
        new ChunkCountInvariantResult(
            DOMAIN.chunkCountExpectation().describe(), documents.size(), chunkViolations);
    assertThat(chunkViolations)
        .as(
            "every corpus document must satisfy '%s' (ADR-0010) — violated by: %s",
            DOMAIN.chunkCountExpectation().describe(), chunkViolations)
        .isEmpty();
    log.info(
        "Chunk-count invariant ('{}') holds for all {} documents",
        DOMAIN.chunkCountExpectation().describe(),
        documents.size());

    // 3b. Issue #721 code review, Nit 4: EvalDomainConfig.maxChunksPerDocument sizes chunkTopK
    //     (DocumentRanking#documentTopKWindowSize) but was never checked against reality — an
    //     undersized declared value would silently undersize chunkTopK and DocumentRanking could
    //     then fail to reach documentTopK distinct documents without any assertion catching why.
    int measuredMaxChunksPerDocument =
        documentChunkCounts.stream()
            .mapToInt(ChunkCountExpectation.DocumentChunkCount::chunkCount)
            .max()
            .orElse(0);
    assertThat(measuredMaxChunksPerDocument)
        .as(
            "EvalDomainConfig.maxChunksPerDocument (%d) must be >= the actually measured maximum "
                + "chunk count per document (%d) in this run — otherwise chunkTopK "
                + "(documentTopK * maxChunksPerDocument) is undersized and DocumentRanking cannot "
                + "reliably reach documentTopK distinct documents after deduplication",
            DOMAIN.maxChunksPerDocument(), measuredMaxChunksPerDocument)
        .isLessThanOrEqualTo(DOMAIN.maxChunksPerDocument());

    // 4. Run every golden query directly against the vector store — retrieval only, no LLM. The
    //    chunk-search window is sized so deduplication (DocumentRanking) can reach documentTopK
    //    distinct documents (ADR-0012 Nachtrag, issue #721) — for comic-characters chunkTopK ==
    //    documentTopK == 10 because maxChunksPerDocument == 1, so this is the same search as before
    //    #721 in every observable way.
    List<GoldenCase> goldenCases = GoldenDataset.load(goldenFile);
    List<RetrievalMetrics.QueryResult> results = new ArrayList<>(goldenCases.size());
    List<ChunkAnswerSpanMetrics.ChunkQueryResult> answerSpanResults = new ArrayList<>();
    List<DocumentRanking.DocumentWindowResult> windowResults = new ArrayList<>(goldenCases.size());
    for (GoldenCase goldenCase : goldenCases) {
      List<org.springframework.ai.document.Document> hits =
          vectorStore.similaritySearch(
              SearchRequest.builder()
                  .query(goldenCase.query())
                  .topK(DOMAIN.chunkTopK())
                  .similarityThreshold(0.0)
                  .build());
      List<String> rankedChunkFileNames =
          hits.stream()
              .map(h -> h.getMetadata().get("file_name"))
              .map(v -> v == null ? null : v.toString())
              .toList();
      var windowResult =
          DocumentRanking.applyDocumentWindow(rankedChunkFileNames, DOMAIN.documentTopK());
      windowResults.add(windowResult);
      results.add(RetrievalMetrics.evaluate(goldenCase, windowResult.rankedFileNames()));

      if (ChunkAnswerSpanMetrics.isApplicable(goldenCase)) {
        List<String> rankedChunkTexts =
            hits.stream().map(org.springframework.ai.document.Document::getText).toList();
        answerSpanResults.add(ChunkAnswerSpanMetrics.evaluate(goldenCase, rankedChunkTexts));
      }
    }
    log.info("Evaluated {} golden queries", results.size());
    ChunkAnswerSpanMetrics.Aggregate answerSpanOverall =
        ChunkAnswerSpanMetrics.aggregate(answerSpanResults);

    // 4a. Issue #721 code review, Wichtig 1: ADR-0012 §8 and the issue's acceptance criteria
    //     promise an explicit report of whether the document-bound window was actually reached —
    //     compute it from the per-query DocumentWindowResult instead of discarding those values.
    int queriesBelowDocumentTopK =
        (int) windowResults.stream().filter(w -> !w.reachedDocumentTopK()).count();
    int minDistinctDocumentsReached =
        windowResults.stream()
            .mapToInt(DocumentRanking.DocumentWindowResult::distinctDocumentsReached)
            .min()
            .orElse(0);
    EvaluationReport.DocumentWindowCoverageResult documentWindowCoverage =
        new EvaluationReport.DocumentWindowCoverageResult(
            windowResults.size(), queriesBelowDocumentTopK, minDistinctDocumentsReached);
    // comic-characters' corpus (1448 documents) is comfortably larger than documentTopK=10, so
    // every query's chunk-bound search must reach the full document window — a query that does not
    // would mean either a corpus/index problem or an undersized chunkTopK, not a fact about this
    // domain the harness should silently accept. A future, deliberately small domain would need to
    // relax or replace this assertion, not this domain.
    assertThat(documentWindowCoverage.alwaysReachedDocumentTopK())
        .as(
            "%d of %d queries did not reach documentTopK=%d distinct documents (min reached: %d) "
                + "— unexpected for a corpus of %d documents",
            queriesBelowDocumentTopK,
            windowResults.size(),
            DOMAIN.documentTopK(),
            minDistinctDocumentsReached,
            manifest.fileNames().size())
        .isTrue();
    log.info(
        "Document window coverage: {} distinct documents reached at minimum across {} queries "
            + "(documentTopK={})",
        minDistinctDocumentsReached,
        windowResults.size(),
        DOMAIN.documentTopK());

    // 4b. Chunk map (issue #721): re-derive each document's real chunk texts through the same
    //     production beans FileProcessingService uses (DocumentService/ChunkingService), so the map
    //     reflects exactly what was indexed. Docker-free in principle (no embedding call needed),
    //     kept here so the map always matches the corpus this specific run actually verified
    // against
    //     the manifest, rather than risking drift from a separately invoked step.
    Map<String, String> answerSpansByCaseId = new LinkedHashMap<>();
    for (GoldenCase goldenCase : goldenCases) {
      if (ChunkAnswerSpanMetrics.isApplicable(goldenCase)) {
        answerSpansByCaseId.put(goldenCase.id(), goldenCase.answerSpan());
      }
    }
    List<ChunkMap.DocumentChunkMap> chunkMaps = new ArrayList<>(manifest.fileNames().size());
    for (String fileName : manifest.fileNames()) {
      Path file = corpusDir.resolve(fileName);
      List<org.springframework.ai.document.Document> parsed = documentService.parseDocument(file);
      String documentText =
          parsed.stream()
              .map(org.springframework.ai.document.Document::getText)
              .reduce("", String::concat);
      List<org.springframework.ai.document.Document> chunks =
          chunkingService.chunkDocuments(fileName, parsed);
      List<String> chunkTexts =
          chunks.stream().map(org.springframework.ai.document.Document::getText).toList();
      Map<String, String> spansForThisDocument = new LinkedHashMap<>();
      for (GoldenCase goldenCase : goldenCases) {
        String span = answerSpansByCaseId.get(goldenCase.id());
        if (span != null && goldenCase.expectedDocuments().contains(fileName)) {
          spansForThisDocument.put(goldenCase.id(), span);
        }
      }
      chunkMaps.add(ChunkMap.build(fileName, documentText, chunkTexts, spansForThisDocument));
    }
    Path chunkMapFile = Path.of("build", "eval-reports", "chunk-map-" + DOMAIN.name() + ".json");
    ChunkMapWriter.write(chunkMaps, chunkMapFile);
    log.info("Chunk map written to {}", chunkMapFile.toAbsolutePath());

    // 4c. Issue #721 code review, Wichtig 3: an answer_span that never resolves to any chunk of any
    //     of its expected_documents (typo, a whitespace difference SpanMatcher does not absorb, or
    //     a chunking-parameter change that pushed it across a chunk boundary) is numerically
    //     indistinguishable from a genuine retrieval failure — both make spanChunkRank=-1. Detect
    // it
    //     explicitly instead of letting it silently masquerade as a chunk-level regression.
    java.util.Set<String> resolvedAnswerSpanCaseIds = new java.util.LinkedHashSet<>();
    for (var documentChunkMap : chunkMaps) {
      resolvedAnswerSpanCaseIds.addAll(documentChunkMap.answerSpanChunkIndexByCaseId().keySet());
    }
    List<String> unresolvedAnswerSpanCaseIds =
        answerSpansByCaseId.keySet().stream()
            .filter(caseId -> !resolvedAnswerSpanCaseIds.contains(caseId))
            .sorted()
            .toList();
    EvaluationReport.AnswerSpanResolutionResult answerSpanResolution =
        new EvaluationReport.AnswerSpanResolutionResult(
            answerSpansByCaseId.size(), unresolvedAnswerSpanCaseIds);
    // Only a hard abort for a domain that actually declares answer_span cases (comic-characters
    // declares none, so this can never fire here) — mirrors the chunk-count invariant's severity:
    // a broken measurement precondition, not a tolerance case.
    if (!answerSpansByCaseId.isEmpty()) {
      assertThat(answerSpanResolution.allResolved())
          .as(
              "%d of %d applicable answer_span cases did not resolve to any chunk of any of their "
                  + "expected_documents (ADR-0012 §9) — unresolved case ids: %s. This is either a "
                  + "broken golden-dataset fixture (typo, unabsorbed whitespace difference) or a "
                  + "chunking-parameter change that pushed the span across a chunk boundary; either "
                  + "way it must not be measured as a chunk-level regression",
              unresolvedAnswerSpanCaseIds.size(),
              answerSpansByCaseId.size(),
              unresolvedAnswerSpanCaseIds)
          .isTrue();
    }
    log.info(
        "Answer-span resolution: {} of {} applicable cases resolved",
        answerSpansByCaseId.size() - unresolvedAnswerSpanCaseIds.size(),
        answerSpansByCaseId.size());

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
            .map(CityLandmarksRetrievalEvaluationHarnessTest::toWorstQuery)
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
            EvalOllamaEndpoint.describeImageOrEndpoint(OLLAMA_IMAGE),
            EMBEDDING_DIMENSIONS,
            actualChunkSize,
            actualChunkSize == EXPECTED_APPLICATION_DEFAULT_CHUNK_SIZE,
            indexingProperties.chunkOverlap(),
            DOMAIN.documentTopK(),
            DOMAIN.chunkTopK(),
            DOMAIN.chunkTopK(),
            PRODUCTION_SIMILARITY_THRESHOLD,
            "similarityThreshold=0.0 was used for every search in this run, not the production "
                + "default above — ranking metrics need the full, unfiltered top-k order; production "
                + "queries do apply the threshold.",
            PGVECTOR_INDEX_TYPE,
            CorpusManifest.sha256Hex(manifestFile),
            manifest.fileNames().size(),
            "eval/golden/" + DOMAIN.goldenDatasetFileName(),
            GoldenDataset.sha256(goldenFile),
            goldenCases.size(),
            runStart.toString(),
            Duration.between(runStart, Instant.now()).toMillis() / 1000.0,
            EvalOllamaEndpoint.isExternal());

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
            answerSpanOverall,
            documentWindowCoverage,
            answerSpanResolution,
            worstQueries,
            allQueryResults);

    // Domain-specific report file name: RetrievalEvaluationHarnessTest (comic-characters) writes
    // to the plain "retrieval-metrics.json" for backward compatibility with existing tooling and
    // CI artifact names; this second harness must not silently overwrite that file when both run
    // in the same job (see .github/workflows/retrieval-regression.yml, issue #234).
    Path reportFile =
        Path.of("build", "eval-reports", "retrieval-metrics-" + DOMAIN.name() + ".json");
    ReportWriter.writeJson(report, reportFile);
    String summary = ReportWriter.renderSummary(report);
    log.info(summary);
    System.out.println("Report written to " + reportFile.toAbsolutePath());

    // 6. Second measurement path (#1039): the same golden cases, the same index, but through the
    //    production query pipeline (steps 2 to 6 of docs/features/retrieval-algorithm.md) instead
    //    of similaritySearch directly. Runs after — never instead of — the raw-vector path above,
    //    whose numbers, report file and baseline are untouched by this block, and guarded so a
    //    failure here cannot fail this test and thereby rob the nightly job of its raw-vector
    //    verdict (see PipelineHarnessSupport).
    Instant pipelineRunStart = Instant.now();
    PipelineHarnessSupport.runAndWriteGuarded(
        DOMAIN,
        new PipelineHarnessSupport.RunIdentity(
            "ollama",
            EMBEDDING_MODEL,
            actualEmbeddingModelDigest,
            EvalOllamaEndpoint.describeImageOrEndpoint(OLLAMA_IMAGE),
            EMBEDDING_DIMENSIONS,
            actualChunkSize == EXPECTED_APPLICATION_DEFAULT_CHUNK_SIZE,
            PGVECTOR_INDEX_TYPE,
            CorpusManifest.sha256Hex(manifestFile),
            manifest.fileNames().size(),
            "eval/golden/" + DOMAIN.goldenDatasetFileName(),
            GoldenDataset.sha256(goldenFile)),
        queryService,
        queryProperties,
        indexingProperties,
        evalLibraryId,
        goldenCases,
        pipelineRunStart,
        log);
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
        r.allExpectedDocumentsHitAt10(),
        r.goldenCase().expectedDocuments(),
        r.rankedFileNames());
  }

  /**
   * Waits for the corpus to finish indexing. PR #730, second review round: the original 45-minute
   * budget (copied from {@link RetrievalEvaluationHarnessTest}'s comic-characters figure under the
   * "comparable order of magnitude" assumption below) turned out to be too tight on a GitHub
   * Actions runner — a real run only reached 78 of 200 documents in 45 minutes (~1.7
   * documents/minute) before this await timed out and the subsequent container teardown cascaded
   * into unrelated-looking connection-pool errors. Extrapolated at that measured rate (78 documents
   * / 45 minutes on that GitHub Actions run), 200 documents need roughly 115 minutes; a first
   * stopgap raised this budget to 90 minutes, which is *below* that extrapolation and was expected
   * to time out again — corrected to 150 minutes (115 minutes extrapolated plus a margin) so the
   * next real run tests an actually sufficient budget instead of repeating the same failure.
   * Locally (non-CI hardware) the full domain, including indexing, evaluation queries and this same
   * corpus, finished in ~36 minutes end to end at the time this budget was measured (see {@code
   * eval/baseline/city-landmarks.json}'s {@code notes}), so this gap is specific to the GitHub
   * Actions runner's embedding throughput, not the corpus itself — see issue #734 for
   * parallelizing/batching the Ollama embedding calls in {@code io.opaa.indexing} (the actual fix,
   * implemented and merged in #735; this budget increase remains in place as a safety margin, not
   * retuned down to a now-possibly-lower measured figure). {@code RANK_NEIGHBOR_RADIUS} itself was
   * later corrected back to 2 (PR #730 verification round — see {@link
   * EvalDomainConfig#CITY_LANDMARKS} for the currently measured chunk-count distribution: median 8,
   * maximum 11, with a configured {@code maxChunksPerDocument} upper bound of 13). The budget must
   * also stay comfortably below the workflow's {@code timeout-minutes} so a genuinely stuck
   * indexing run fails here — with a diagnosable test failure — instead of being killed as a
   * cancelled job.
   */
  private void awaitJobCompletion(IndexingJob job) {
    await()
        .atMost(150, TimeUnit.MINUTES)
        .pollInterval(2, TimeUnit.SECONDS)
        .until(
            () -> {
              var latestJob = indexingJobRepository.findById(job.getId()).orElseThrow();
              return latestJob.getStatus() != JobStatus.RUNNING;
            });
  }
}
