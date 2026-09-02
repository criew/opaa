package io.opaa.searchadmin;

import io.opaa.indexing.FullTextBackfillProgress;
import io.opaa.indexing.FullTextBackfillProgressService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.llm.LlmModelService;
import io.opaa.llm.RerankRoleState;
import io.opaa.llm.RerankRoleStatus;
import io.opaa.llm.RerankRoleStatusProvider;
import io.opaa.query.QueryProperties;
import io.opaa.query.RetrievalPipelineProperties;
import io.opaa.query.RetrievalStageName;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * Assembles the read-only status of retrieval for one organization
 * (docs/features/hybrid-retrieval.md, "Was die Seite anzeigt").
 *
 * <p><b>Read-only by construction.</b> This service has no method that changes anything, and the
 * records it returns carry no access key in any form.
 *
 * <p><b>The full-text fill state comes from {@link FullTextBackfillProgressService} and nowhere
 * else</b> - the same query the completion gate reads before it lets the lexical path search a
 * library. A second count with its own logic could show "vollständig" against a gate that refuses
 * the library on a different rule, which is precisely the confusion this page exists to end. The
 * two can still disagree for a moment: this page reads the count fresh on every load, while {@code
 * FullTextBackfillGate} keeps an incomplete library's answer for its recheck interval, so a
 * backfill that just finished shows as complete here up to that interval before the gate lets the
 * library into the fusion.
 *
 * <p><b>The two reachability probes are bounded in time and shared across callers.</b> Without that
 * bound every page load costs one chat and one embedding round trip per administrator, per
 * navigation and per StrictMode double mount, and an unresponsive embedding endpoint holds the
 * request thread for as long as it likes - the Spring AI client this service borrows carries no
 * timeout of our own making. A probe pair is therefore reused process-wide for {@link
 * #PROBE_CACHE_TTL}, and the embedding probe is bounded by {@link #EMBEDDING_PROBE_TIMEOUT} so both
 * roles report an unresponsive endpoint the same way.
 */
@Service
public class SearchStatusService {

  private static final Logger log = LoggerFactory.getLogger(SearchStatusService.class);

  /** Sent to the embedding endpoint purely to see whether it answers. */
  private static final String REACHABILITY_PROBE_TEXT = "Erreichbarkeitspruefung";

  /** How long one probe pair answers every caller. Short enough to still be a live status. */
  static final Duration PROBE_CACHE_TTL = Duration.ofSeconds(45);

  /** The same bound {@link LlmModelConnectionTester} puts on the chat probe. */
  static final Duration EMBEDDING_PROBE_TIMEOUT = Duration.ofSeconds(10);

  private final LlmModelService llmModelService;
  private final LlmModelConnectionTester connectionTester;
  private final EmbeddingInfoService embeddingInfoService;
  private final EmbeddingModel embeddingModel;
  private final RerankRoleStatusProvider rerankRoleStatusProvider;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryDocumentStatsReader documentStatsReader;
  private final FullTextBackfillProgressService fullTextBackfillProgressService;
  private final QueryProperties queryProperties;
  private final RetrievalPipelineProperties pipelineProperties;
  private final Clock clock;

  /**
   * Daemon threads: a probe hanging on an unresponsive endpoint must not keep the JVM from shutting
   * down. Bounded to two threads, because {@link Future#cancel(boolean)} cannot interrupt a
   * blocking socket read in the Spring AI client - against a permanently unresponsive endpoint an
   * unbounded pool would leak one thread and one connection per probe. A caller whose probe finds
   * both threads occupied runs into {@link #EMBEDDING_PROBE_TIMEOUT} and reports the endpoint as
   * unreachable, which is what an endpoint that hangs on every call in fact is.
   */
  private final ExecutorService probeExecutor =
      Executors.newFixedThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "search-status-probe");
            thread.setDaemon(true);
            return thread;
          });

  private final Object probeLock = new Object();
  private volatile ProbedRoles probedRoles;

  /** The two network-probed roles together with the instant their shared result goes stale. */
  private record ProbedRoles(ModelRoleStatus chat, ModelRoleStatus embedding, Instant expiresAt) {}

  /**
   * A probed role and whether the result says anything about the endpoint. A result caused by the
   * calling thread alone is answered to that caller but never shared, so a single interrupted
   * request cannot show every administrator a fault for a whole {@link #PROBE_CACHE_TTL}.
   */
  private record ProbeOutcome(ModelRoleStatus status, boolean cacheable) {}

  public SearchStatusService(
      LlmModelService llmModelService,
      LlmModelConnectionTester connectionTester,
      EmbeddingInfoService embeddingInfoService,
      EmbeddingModel embeddingModel,
      RerankRoleStatusProvider rerankRoleStatusProvider,
      KnowledgeLibraryRepository libraryRepository,
      LibraryDocumentStatsReader documentStatsReader,
      FullTextBackfillProgressService fullTextBackfillProgressService,
      QueryProperties queryProperties,
      RetrievalPipelineProperties pipelineProperties,
      Clock clock) {
    this.llmModelService = llmModelService;
    this.connectionTester = connectionTester;
    this.embeddingInfoService = embeddingInfoService;
    this.embeddingModel = embeddingModel;
    this.rerankRoleStatusProvider = rerankRoleStatusProvider;
    this.libraryRepository = libraryRepository;
    this.documentStatsReader = documentStatsReader;
    this.fullTextBackfillProgressService = fullTextBackfillProgressService;
    this.queryProperties = queryProperties;
    this.pipelineProperties = pipelineProperties;
    this.clock = clock;
  }

  @PreDestroy
  void stopProbing() {
    probeExecutor.shutdownNow();
  }

  /** The whole status display for {@code organizationId}, libraries by name. */
  public SearchStatus statusForOrganization(UUID organizationId) {
    List<LibrarySearchStatus> libraries = libraryStatus(organizationId);
    return new SearchStatus(modelRoles(), searchPaths(libraries), libraries);
  }

  /**
   * Chat and embedding come from the cached probe pair; the rerank role is read fresh because its
   * provider contract forbids a network round trip to begin with.
   */
  private List<ModelRoleStatus> modelRoles() {
    ProbedRoles probed = currentProbes();
    return List.of(probed.chat(), probed.embedding(), rerankRole());
  }

  /**
   * One probe pair per {@link #PROBE_CACHE_TTL}, however many administrators look at the page at
   * once: the refresh runs under a lock, so concurrent callers wait for the running probe instead
   * of starting their own.
   */
  private ProbedRoles currentProbes() {
    ProbedRoles cached = probedRoles;
    if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
      return cached;
    }
    synchronized (probeLock) {
      ProbedRoles current = probedRoles;
      if (current != null && clock.instant().isBefore(current.expiresAt())) {
        return current;
      }
      ModelRoleStatus chat = chatRole();
      ProbeOutcome embedding = embeddingRole();
      ProbedRoles refreshed =
          new ProbedRoles(chat, embedding.status(), clock.instant().plus(PROBE_CACHE_TTL));
      if (embedding.cacheable()) {
        probedRoles = refreshed;
      }
      return refreshed;
    }
  }

  /**
   * The systemwide active chat model, probed through the same connection test the model
   * administration offers - with a {@code null} key, so the model's own stored key is reused and no
   * plaintext key is ever handled here (see {@link LlmModelConnectionTester#test}).
   */
  private ModelRoleStatus chatRole() {
    Optional<LlmModel> active =
        llmModelService.listModels().stream().filter(LlmModel::isActive).findFirst();
    if (active.isEmpty()) {
      return new ModelRoleStatus(
          ModelRole.CHAT,
          ModelRoleCondition.UNCONFIGURED,
          null,
          null,
          "Es ist kein Chat-Modell aktiv. Ohne aktives Modell kann keine Frage beantwortet werden.");
    }
    LlmModel model = active.get();
    LlmModelConnectionTester.TestOutcome outcome;
    try {
      outcome =
          connectionTester.test(
              model.getBaseUrl(), model.getModelIdentifier(), null, model.getId());
    } catch (RuntimeException e) {
      log.warn("Chat role reachability probe failed: {}", e.getMessage());
      return new ModelRoleStatus(
          ModelRole.CHAT,
          ModelRoleCondition.UNREACHABLE,
          model.getBaseUrl(),
          model.getModelIdentifier(),
          "Die Erreichbarkeit des Chat-Modells konnte nicht geprüft werden.");
    }
    // outcome.message() is the same German text the model administration's own connection test
    // shows; it names the concrete cause (falsche Adresse, 401, unbekannte Modell-Kennung).
    return new ModelRoleStatus(
        ModelRole.CHAT,
        outcome.success() ? ModelRoleCondition.ACTIVE : ModelRoleCondition.UNREACHABLE,
        model.getBaseUrl(),
        model.getModelIdentifier(),
        outcome.message());
  }

  /**
   * The configured embedding model, probed with one embedding call - the same probe {@code
   * EmbeddingsHealthIndicator} makes, and the only way to tell a configured endpoint from a
   * reachable one.
   */
  private ProbeOutcome embeddingRole() {
    EmbeddingInfo info = embeddingInfoService.getEmbeddingInfo();
    // The submit belongs inside the try: a rejected probe is one unreachable role, not a failed
    // status endpoint.
    Future<float[]> probe = null;
    try {
      probe = probeExecutor.submit(() -> embeddingModel.embed(REACHABILITY_PROBE_TEXT));
      probe.get(EMBEDDING_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return new ProbeOutcome(
          new ModelRoleStatus(
              ModelRole.EMBEDDING,
              ModelRoleCondition.ACTIVE,
              null,
              info.model(),
              "Das Einbettungsmodell hat auf die Erreichbarkeitsprüfung geantwortet."),
          true);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      probe.cancel(true);
      return new ProbeOutcome(unreachableEmbedding(info), false);
    } catch (ExecutionException | TimeoutException | RuntimeException e) {
      if (probe != null) {
        probe.cancel(true);
      }
      log.warn("Embedding role reachability probe failed: {}", e.toString());
      return new ProbeOutcome(unreachableEmbedding(info), true);
    }
  }

  private static ModelRoleStatus unreachableEmbedding(EmbeddingInfo info) {
    return new ModelRoleStatus(
        ModelRole.EMBEDDING,
        ModelRoleCondition.UNREACHABLE,
        null,
        info.model(),
        "Das Einbettungsmodell hat auf die Erreichbarkeitsprüfung nicht geantwortet. Ohne"
            + " Einbettungen findet die Vektorsuche nichts.");
  }

  /**
   * Read through the {@link RerankRoleStatusProvider} contract only - this page observes the rerank
   * role, it does not know how the role is built (#1050).
   */
  private ModelRoleStatus rerankRole() {
    RerankRoleStatus status = rerankRoleStatusProvider.currentStatus();
    if (status.diagnostic() != null) {
      log.debug("Rerank role reports {}: {}", status.state(), status.diagnostic());
    }
    return new ModelRoleStatus(
        ModelRole.RERANK,
        conditionOf(status.state()),
        status.baseUrl(),
        status.modelIdentifier(),
        rerankDetail(status.state()));
  }

  private static ModelRoleCondition conditionOf(RerankRoleState state) {
    return switch (state) {
      case DISABLED -> ModelRoleCondition.DISABLED;
      case READY -> ModelRoleCondition.ACTIVE;
      case UNCONFIGURED -> ModelRoleCondition.UNCONFIGURED;
      case UNREACHABLE -> ModelRoleCondition.UNREACHABLE;
    };
  }

  private static String rerankDetail(RerankRoleState state) {
    return switch (state) {
      case DISABLED ->
          "Reranking ist ausdrücklich abgeschaltet. Die Suche läuft ohne diese Stufe - das ist"
              + " die Voreinstellung, kein Fehler.";
      case READY -> "Reranking ist eingeschaltet und der Endpunkt antwortet.";
      case UNCONFIGURED ->
          "Reranking ist eingeschaltet, aber es ist keine Rerank-Modellrolle hinterlegt. Die Suche"
              + " läuft weiter - ohne diese Stufe.";
      case UNREACHABLE ->
          "Reranking ist eingeschaltet, aber der hinterlegte Endpunkt antwortet nicht. Die Suche"
              + " läuft weiter - ohne diese Stufe.";
    };
  }

  /**
   * A path switched off at stage level or by its own property reports {@code DISABLED}; a running
   * path that cannot yet cover every library reports {@code INCOMPLETE}. For the full-text path the
   * incomplete count is the number of libraries the completion gate would still refuse.
   */
  private List<SearchPathStatus> searchPaths(List<LibrarySearchStatus> libraries) {
    long withChunks = libraries.stream().filter(l -> l.vectorChunkCount() > 0).count();
    long vectorIncomplete =
        libraries.stream()
            .filter(l -> l.vectorIndexCondition() == LibrarySearchStatus.IndexCondition.INCOMPLETE)
            .count();
    long fullTextIncomplete =
        libraries.stream()
            .filter(
                l -> l.fullTextIndexCondition() == LibrarySearchStatus.IndexCondition.INCOMPLETE)
            .count();

    boolean vectorDisabled =
        pipelineProperties.disabledStages().contains(RetrievalStageName.VECTOR_SEARCH);
    boolean fullTextDisabled =
        !queryProperties.fullTextSearchEnabled()
            || pipelineProperties.disabledStages().contains(RetrievalStageName.FULL_TEXT_SEARCH);

    return List.of(
        new SearchPathStatus(
            SearchPathStatus.SearchPathName.VECTOR,
            pathCondition(vectorDisabled, vectorIncomplete),
            vectorIncomplete,
            withChunks),
        new SearchPathStatus(
            SearchPathStatus.SearchPathName.FULL_TEXT,
            pathCondition(fullTextDisabled, fullTextIncomplete),
            fullTextIncomplete,
            withChunks));
  }

  private static SearchPathStatus.SearchPathCondition pathCondition(
      boolean disabled, long incompleteLibraries) {
    if (disabled) {
      return SearchPathStatus.SearchPathCondition.DISABLED;
    }
    return incompleteLibraries > 0
        ? SearchPathStatus.SearchPathCondition.INCOMPLETE
        : SearchPathStatus.SearchPathCondition.ACTIVE;
  }

  private List<LibrarySearchStatus> libraryStatus(UUID organizationId) {
    Map<UUID, LibraryDocumentStats> statsByLibrary =
        documentStatsReader.statsForOrganization(organizationId);
    List<KnowledgeLibrary> libraries = libraryRepository.findByOrganizationId(organizationId);
    // Only this organization's libraries are counted; an unfiltered progress read would scan the
    // whole vector store across organizations for rows this page never shows. The remaining scan
    // cost is the missing expression index (#1119), see the Javadoc of
    // FullTextBackfillProgressService#progressForLibraries.
    Set<UUID> libraryIds = new LinkedHashSet<>();
    for (KnowledgeLibrary library : libraries) {
      libraryIds.add(library.getId());
    }
    Map<UUID, FullTextBackfillProgress> progressByLibrary = new HashMap<>();
    for (FullTextBackfillProgress progress :
        fullTextBackfillProgressService.progressForLibraries(libraryIds)) {
      progressByLibrary.put(progress.libraryId(), progress);
    }

    List<LibrarySearchStatus> result = new ArrayList<>();
    for (KnowledgeLibrary library : libraries) {
      LibraryDocumentStats stats =
          statsByLibrary.getOrDefault(library.getId(), LibraryDocumentStats.empty(library.getId()));
      FullTextBackfillProgress progress =
          progressByLibrary.getOrDefault(
              library.getId(), new FullTextBackfillProgress(library.getId(), 0, 0, 0, 0));
      result.add(
          new LibrarySearchStatus(
              library.getId(),
              library.getName(),
              stats.documentCount(),
              stats.indexedDocumentCount(),
              stats.pendingDocumentCount(),
              stats.failedDocumentCount(),
              stats.lowChunkDocumentCount(),
              stats.chunkCount(),
              progress.totalChunks(),
              stats.lastIndexedAt(),
              progress.indexedChunks(),
              progress.missingChunks(),
              progress.skippedChunks()));
    }
    result.sort(
        Comparator.comparing(LibrarySearchStatus::libraryName, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(result);
  }
}
