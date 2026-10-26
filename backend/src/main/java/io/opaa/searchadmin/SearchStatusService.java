package io.opaa.searchadmin;

import io.opaa.api.types.ModelRole;
import io.opaa.api.types.ModelRoleCondition;
import io.opaa.api.types.RetrievalStageName;
import io.opaa.compliance.DataFlow;
import io.opaa.indexing.chunk.EmbeddingIndexState;
import io.opaa.indexing.maintenance.ContextPrefixRerunProgress;
import io.opaa.indexing.maintenance.ContextPrefixRerunService;
import io.opaa.indexing.maintenance.FullTextIndexFillState;
import io.opaa.indexing.maintenance.FullTextIndexFillStateService;
import io.opaa.indexing.maintenance.MetadataBackfillProgress;
import io.opaa.indexing.maintenance.MetadataBackfillService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.library.PrivateLibraryDeletionRun;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.llm.LlmModelService;
import io.opaa.llm.RerankRoleState;
import io.opaa.llm.RerankRoleStatus;
import io.opaa.llm.RerankRoleStatusProvider;
import io.opaa.llm.TranscriptionRoleStatus;
import io.opaa.llm.TranscriptionRoleStatusProvider;
import io.opaa.metadata.LibraryMetadataSchemaChangeProgress;
import io.opaa.metadata.LibraryMetadataSchemaChangeService;
import io.opaa.metadata.ModelExtractionCounters;
import io.opaa.metadata.ModelExtractionStats;
import io.opaa.permission.PersonThreshold;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RetrievalPipelineProperties;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Assembles the read-only status of retrieval for one organization
 * (docs/features/hybrid-retrieval.md, "Was die Seite anzeigt").
 *
 * <p><b>Read-only by construction.</b> This service has no method that changes anything, and the
 * records it returns carry no access key in any form.
 *
 * <p><b>The full-text fill state comes from {@link FullTextIndexFillStateService} and nowhere
 * else</b>, read fresh on every load - a second count with its own logic could show "vollständig"
 * for a library whose chunks the lexical path cannot find, which is precisely the confusion this
 * page exists to end.
 *
 * <p><b>The two reachability probes are bounded in time and shared across callers.</b> Without that
 * bound every page load costs one chat and one embedding round trip per administrator, per
 * navigation and per StrictMode double mount, and an unresponsive embedding endpoint holds the
 * request thread for as long as it likes - the Spring AI client this service borrows carries no
 * timeout of our own making. A probe result is therefore shared by every instance for {@link
 * #PROBE_CACHE_TTL} ({@link SearchStatusProbeResults}, ADR-0053, Entscheidung 12), so the page
 * shows the same state whichever instance answers it, and the embedding probe is bounded by {@link
 * #EMBEDDING_PROBE_TIMEOUT} so both roles report an unresponsive endpoint the same way.
 */
@DataFlow("embedding-model")
@Service
public class SearchStatusService {

  private static final Logger log = LoggerFactory.getLogger(SearchStatusService.class);

  /** Sent to the embedding endpoint purely to see whether it answers. */
  private static final String REACHABILITY_PROBE_TEXT = "Erreichbarkeitspruefung";

  /** How long one probe result answers every caller. Short enough to still be a live status. */
  static final Duration PROBE_CACHE_TTL = Duration.ofSeconds(45);

  /** The same bound {@link LlmModelConnectionTester} puts on the chat probe. */
  static final Duration EMBEDDING_PROBE_TIMEOUT = Duration.ofSeconds(10);

  private final LlmModelService llmModelService;
  private final LlmModelConnectionTester connectionTester;
  private final EmbeddingInfoService embeddingInfoService;
  private final EmbeddingModel embeddingModel;
  private final RerankRoleStatusProvider rerankRoleStatusProvider;
  private final TranscriptionRoleStatusProvider transcriptionRoleStatusProvider;
  private final KnowledgeLibraryRepository libraryRepository;
  private final LibraryDocumentStatsReader documentStatsReader;
  private final FullTextIndexFillStateService fullTextIndexFillStateService;
  private final MetadataBackfillService metadataBackfillService;
  private final ModelExtractionCounters modelExtractionCounters;
  private final ContextPrefixRerunService contextPrefixRerunService;
  private final LibraryMetadataSchemaChangeService schemaChangeService;
  private final QueryProperties queryProperties;
  private final RetrievalPipelineProperties pipelineProperties;
  private final PersonThreshold threshold;
  private final PrivateLibraryDeletionRun deletionRun;
  private final EmbeddingIndexState embeddingIndexState;
  private final SearchStatusProbeResults probeResults;

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

  /** The two network-probed roles. */
  private record ProbedRoles(ModelRoleStatus chat, ModelRoleStatus embedding) {}

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
      FullTextIndexFillStateService fullTextIndexFillStateService,
      MetadataBackfillService metadataBackfillService,
      ModelExtractionCounters modelExtractionCounters,
      ContextPrefixRerunService contextPrefixRerunService,
      LibraryMetadataSchemaChangeService schemaChangeService,
      QueryProperties queryProperties,
      RetrievalPipelineProperties pipelineProperties,
      PersonThreshold threshold,
      Clock clock) {
    this(
        llmModelService,
        connectionTester,
        embeddingInfoService,
        embeddingModel,
        rerankRoleStatusProvider,
        libraryRepository,
        documentStatsReader,
        fullTextIndexFillStateService,
        metadataBackfillService,
        modelExtractionCounters,
        contextPrefixRerunService,
        schemaChangeService,
        queryProperties,
        pipelineProperties,
        threshold,
        null,
        new EmbeddingIndexState(),
        TranscriptionRoleStatus::disabled,
        new ProcessLocalSearchStatusProbeResults(clock));
  }

  /**
   * {@code deletionRun}, when given, tells how many private libraries are due to be erased; {@code
   * embeddingIndexState} whether the vector path is suspended; {@code probeResults} shares the
   * probes of chat and embedding role.
   */
  @Autowired
  public SearchStatusService(
      LlmModelService llmModelService,
      LlmModelConnectionTester connectionTester,
      EmbeddingInfoService embeddingInfoService,
      EmbeddingModel embeddingModel,
      RerankRoleStatusProvider rerankRoleStatusProvider,
      KnowledgeLibraryRepository libraryRepository,
      LibraryDocumentStatsReader documentStatsReader,
      FullTextIndexFillStateService fullTextIndexFillStateService,
      MetadataBackfillService metadataBackfillService,
      ModelExtractionCounters modelExtractionCounters,
      ContextPrefixRerunService contextPrefixRerunService,
      LibraryMetadataSchemaChangeService schemaChangeService,
      QueryProperties queryProperties,
      RetrievalPipelineProperties pipelineProperties,
      PersonThreshold threshold,
      PrivateLibraryDeletionRun deletionRun,
      EmbeddingIndexState embeddingIndexState,
      TranscriptionRoleStatusProvider transcriptionRoleStatusProvider,
      SearchStatusProbeResults probeResults) {
    this.transcriptionRoleStatusProvider = transcriptionRoleStatusProvider;
    this.probeResults = probeResults;
    this.deletionRun = deletionRun;
    this.embeddingIndexState = embeddingIndexState;
    this.threshold = threshold;
    this.llmModelService = llmModelService;
    this.connectionTester = connectionTester;
    this.embeddingInfoService = embeddingInfoService;
    this.embeddingModel = embeddingModel;
    this.rerankRoleStatusProvider = rerankRoleStatusProvider;
    this.libraryRepository = libraryRepository;
    this.documentStatsReader = documentStatsReader;
    this.fullTextIndexFillStateService = fullTextIndexFillStateService;
    this.metadataBackfillService = metadataBackfillService;
    this.modelExtractionCounters = modelExtractionCounters;
    this.contextPrefixRerunService = contextPrefixRerunService;
    this.schemaChangeService = schemaChangeService;
    this.queryProperties = queryProperties;
    this.pipelineProperties = pipelineProperties;
  }

  @PreDestroy
  void stopProbing() {
    probeExecutor.shutdownNow();
  }

  /**
   * The whole status display for {@code organizationId}: the shared libraries by name, the private
   * ones summed up without names.
   */
  public SearchStatus statusForOrganization(UUID organizationId) {
    Map<UUID, LibraryDocumentStats> statsByLibrary =
        documentStatsReader.statsForOrganization(organizationId);
    List<LibrarySearchStatus> libraries = libraryStatus(organizationId, statsByLibrary);
    return new SearchStatus(
        modelRoles(),
        searchPaths(libraries),
        libraries,
        privateSummary(organizationId, libraries, statsByLibrary));
  }

  /**
   * The private libraries as one line: every document row not of a listed shared library is one of
   * a private library, and the libraries themselves are only counted.
   */
  private PrivateLibrarySummary privateSummary(
      UUID organizationId,
      List<LibrarySearchStatus> shared,
      Map<UUID, LibraryDocumentStats> statsByLibrary) {
    Set<UUID> sharedIds = new HashSet<>();
    for (LibrarySearchStatus library : shared) {
      sharedIds.add(library.libraryId());
    }
    long documents = 0;
    long failed = 0;
    long chunks = 0;
    for (LibraryDocumentStats stats : statsByLibrary.values()) {
      if (!sharedIds.contains(stats.libraryId())) {
        documents += stats.documentCount();
        failed += stats.failedDocumentCount();
        chunks += stats.chunkCount();
      }
    }
    PrivateLibrarySummary.ScheduledPart scheduled = null;
    if (deletionRun != null) {
      PrivateLibraryDeletionRun.ScheduledErasures due = deletionRun.scheduledIn(organizationId);
      scheduled =
          new PrivateLibrarySummary.ScheduledPart(due.libraries(), due.owners(), due.otherOwners());
    }
    return PrivateLibrarySummary.of(
        libraryRepository.countByOrganizationIdAndOwnerOnlyTrue(organizationId),
        libraryRepository.countPrivateLibraryOwners(organizationId),
        new LibraryDocumentStats(null, documents, 0, 0, failed, 0, chunks, null),
        threshold,
        scheduled);
  }

  /**
   * Chat and embedding come from the shared probe results; the rerank and transcription roles are
   * read fresh because their provider contracts forbid a network round trip to begin with.
   */
  private List<ModelRoleStatus> modelRoles() {
    ProbedRoles probed = currentProbes();
    return List.of(probed.chat(), probed.embedding(), rerankRole(), transcriptionRole());
  }

  /** Drops the shared probe results; the next status probes again. */
  void forgetProbes() {
    probeResults.forget();
  }

  /**
   * The results shared within {@link #PROBE_CACHE_TTL}, probing only the roles without one. On this
   * instance the probes run under a lock, so concurrent callers wait for the running probe instead
   * of starting their own; across instances the first shared result wins.
   */
  private ProbedRoles currentProbes() {
    Optional<ModelRoleStatus> chat = probeResults.fresh(ModelRole.CHAT, PROBE_CACHE_TTL);
    Optional<ModelRoleStatus> embedding = probeResults.fresh(ModelRole.EMBEDDING, PROBE_CACHE_TTL);
    if (chat.isPresent() && embedding.isPresent()) {
      return new ProbedRoles(chat.get(), embedding.get());
    }
    synchronized (probeLock) {
      ModelRoleStatus sharedChat =
          probeResults
              .fresh(ModelRole.CHAT, PROBE_CACHE_TTL)
              .orElseGet(() -> probeResults.share(chatRole(), PROBE_CACHE_TTL));
      ModelRoleStatus sharedEmbedding =
          probeResults
              .fresh(ModelRole.EMBEDDING, PROBE_CACHE_TTL)
              .orElseGet(
                  () -> {
                    ProbeOutcome probed = embeddingRole();
                    return probed.cacheable()
                        ? probeResults.share(probed.status(), PROBE_CACHE_TTL)
                        : probed.status();
                  });
      return new ProbedRoles(sharedChat, sharedEmbedding);
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
              model.getBaseUrl(),
              model.getModelIdentifier(),
              null,
              model.getId(),
              model.getTemperature());
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
              info.baseUrl(),
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
        info.baseUrl(),
        info.model(),
        "Das Einbettungsmodell hat auf die Erreichbarkeitsprüfung nicht geantwortet. Ohne"
            + " Einbettungen findet die Vektorsuche nichts.");
  }

  /**
   * Read through the {@link RerankRoleStatusProvider} contract only - this page observes the rerank
   * role, it does not know how the role is built.
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
        rerankDetail(status));
  }

  private static ModelRoleCondition conditionOf(RerankRoleState state) {
    return switch (state) {
      case DISABLED -> ModelRoleCondition.DISABLED;
      case READY -> ModelRoleCondition.ACTIVE;
      case UNCONFIGURED -> ModelRoleCondition.UNCONFIGURED;
      case UNREACHABLE -> ModelRoleCondition.UNREACHABLE;
    };
  }

  /**
   * A timed-out {@link RerankRoleState#UNREACHABLE} gets its own wording: "antwortet nicht" reads
   * as a dead endpoint, which a CPU reranker still working within {@code opaa.rerank.timeout} is
   * not - it is slow, and the remedy (raise the timeout for its hardware) differs from the remedy
   * for an actually unreachable one.
   */
  private static String rerankDetail(RerankRoleStatus status) {
    return switch (status.state()) {
      case DISABLED ->
          "Reranking ist ausdrücklich abgeschaltet. Die Suche läuft ohne diese Stufe - das ist"
              + " die Voreinstellung, kein Fehler.";
      case READY -> "Reranking ist eingeschaltet und der Endpunkt antwortet.";
      case UNCONFIGURED ->
          status.baseUrlRejected()
              ? "Reranking ist eingeschaltet, aber die hinterlegte Basis-Adresse enthält"
                  + " Anmeldedaten (Form \"https://benutzer:passwort@host\") und wird deshalb"
                  + " abgelehnt. Tragen Sie unter Modelle → Reranking die Adresse ohne Anmeldedaten"
                  + " und den Zugangsschlüssel im eigenen Feld ein. Die Suche läuft weiter - ohne"
                  + " diese Stufe."
              : "Reranking ist eingeschaltet, aber unter Modelle → Reranking fehlen Basis-Adresse"
                  + " oder Modell. Die Suche läuft weiter - ohne diese Stufe.";
      case UNREACHABLE ->
          status.timedOut()
              ? "Reranking ist eingeschaltet, aber der hinterlegte Endpunkt hat nicht innerhalb"
                  + " des Zeitbudgets (OPAA_RERANK_TIMEOUT) geantwortet. Das kann Langsamkeit"
                  + " sein statt eines Ausfalls, etwa bei Reranking auf einer CPU - prüfen Sie,"
                  + " ob das Zeitbudget zur Hardware passt. Die Suche läuft weiter - ohne diese"
                  + " Stufe."
              : "Reranking ist eingeschaltet, aber der hinterlegte Endpunkt antwortet nicht. Die"
                  + " Suche läuft weiter - ohne diese Stufe.";
    };
  }

  /** Read through {@link TranscriptionRoleStatusProvider} only, like the rerank role. */
  private ModelRoleStatus transcriptionRole() {
    TranscriptionRoleStatus status = transcriptionRoleStatusProvider.currentStatus();
    if (status.diagnostic() != null) {
      log.debug("Transcription role reports {}: {}", status.state(), status.diagnostic());
    }
    return transcriptionRole(status);
  }

  /** The role's state, endpoint and model as the status page shows them; no usage figures. */
  static ModelRoleStatus transcriptionRole(TranscriptionRoleStatus status) {
    return new ModelRoleStatus(
        ModelRole.TRANSCRIPTION,
        switch (status.state()) {
          case DISABLED -> ModelRoleCondition.DISABLED;
          case READY -> ModelRoleCondition.ACTIVE;
          case UNCONFIGURED -> ModelRoleCondition.UNCONFIGURED;
          case UNREACHABLE -> ModelRoleCondition.UNREACHABLE;
        },
        status.baseUrl(),
        status.modelIdentifier(),
        transcriptionDetail(status));
  }

  private static String transcriptionDetail(TranscriptionRoleStatus status) {
    return switch (status.state()) {
      case DISABLED ->
          "Die Spracheingabe ist abgeschaltet (Modelle → Spracheingabe oder die Funktion"
              + " Spracheingabe des Betriebs). Im Chat erscheint kein Mikrofon - das ist die Voreinstellung,"
              + " kein Fehler.";
      case READY ->
          "Die Spracheingabe ist eingeschaltet und der Endpunkt antwortet. Im Chat erscheint das"
              + " Mikrofon.";
      case UNCONFIGURED ->
          status.baseUrlRejected()
              ? "Die Spracheingabe ist eingeschaltet, aber die Basis-Adresse enthält Anmeldedaten"
                  + " und wird abgelehnt. Tragen Sie unter Modelle → Spracheingabe die Adresse ohne"
                  + " Anmeldedaten und den Schlüssel im eigenen Feld ein. Bis dahin erscheint im"
                  + " Chat kein Mikrofon."
              : "Die Spracheingabe ist eingeschaltet, aber unter Modelle → Spracheingabe fehlen"
                  + " Basis-Adresse oder Modell. Bis dahin erscheint im Chat kein Mikrofon.";
      case UNREACHABLE ->
          status.timedOut()
              ? "Die Spracheingabe ist eingeschaltet, aber der Endpunkt hat nicht innerhalb des"
                  + " Zeitbudgets (OPAA_TRANSCRIPTION_TIMEOUT) geantwortet. Bis er wieder"
                  + " antwortet, erscheint im Chat kein Mikrofon."
              : "Die Spracheingabe ist eingeschaltet, aber der Endpunkt antwortet nicht. Bis er"
                  + " wieder antwortet, erscheint im Chat kein Mikrofon.";
    };
  }

  /**
   * A path switched off at stage level or by its own property reports {@code DISABLED}. Otherwise
   * each path reports the condition of the libraries it covers: {@code INCOMPLETE} for the vector
   * path while documents are still waiting, {@code OUTDATED} for the full-text path while rows sit
   * below the current tsv version. A stale vector index suspends the vector path entirely.
   */
  private List<SearchPathStatus> searchPaths(List<LibrarySearchStatus> libraries) {
    long withChunks = libraries.stream().filter(l -> l.vectorChunkCount() > 0).count();
    long vectorIncomplete =
        libraries.stream()
            .filter(l -> l.vectorIndexCondition() == LibrarySearchStatus.IndexCondition.INCOMPLETE)
            .count();
    long fullTextOutdated =
        libraries.stream()
            .filter(l -> l.fullTextIndexCondition() == LibrarySearchStatus.IndexCondition.OUTDATED)
            .count();

    boolean vectorDisabled =
        pipelineProperties.disabledStages().contains(RetrievalStageName.VECTOR_SEARCH);
    boolean fullTextDisabled =
        !queryProperties.fullTextSearchEnabled()
            || pipelineProperties.disabledStages().contains(RetrievalStageName.FULL_TEXT_SEARCH);

    SearchPathStatus.SearchPathCondition vectorCondition =
        !vectorDisabled && !embeddingIndexState.isVectorSearchAvailable()
            ? SearchPathStatus.SearchPathCondition.SUSPENDED
            : pathCondition(
                vectorDisabled, vectorIncomplete, SearchPathStatus.SearchPathCondition.INCOMPLETE);
    return List.of(
        new SearchPathStatus(
            SearchPathStatus.SearchPathName.VECTOR, vectorCondition, vectorIncomplete, withChunks),
        new SearchPathStatus(
            SearchPathStatus.SearchPathName.FULL_TEXT,
            pathCondition(
                fullTextDisabled, fullTextOutdated, SearchPathStatus.SearchPathCondition.OUTDATED),
            fullTextOutdated,
            withChunks));
  }

  private static SearchPathStatus.SearchPathCondition pathCondition(
      boolean disabled, long affectedLibraries, SearchPathStatus.SearchPathCondition ifAffected) {
    if (disabled) {
      return SearchPathStatus.SearchPathCondition.DISABLED;
    }
    return affectedLibraries > 0 ? ifAffected : SearchPathStatus.SearchPathCondition.ACTIVE;
  }

  private List<LibrarySearchStatus> libraryStatus(
      UUID organizationId, Map<UUID, LibraryDocumentStats> statsByLibrary) {
    List<KnowledgeLibrary> libraries = libraryRepository.findSharedByOrganizationId(organizationId);
    // Only this organization's libraries are counted; an unfiltered progress read would scan the
    // whole vector store across organizations for rows this page never shows, even with the
    // expression index (see FullTextIndexFillStateService#fillStateForLibraries).
    Set<UUID> libraryIds = new LinkedHashSet<>();
    for (KnowledgeLibrary library : libraries) {
      libraryIds.add(library.getId());
    }
    Map<UUID, FullTextIndexFillState> fillStateByLibrary = new HashMap<>();
    for (FullTextIndexFillState fillState :
        fullTextIndexFillStateService.fillStateForLibraries(libraryIds)) {
      fillStateByLibrary.put(fillState.libraryId(), fillState);
    }
    // Read from the same selection column the backfill drains (metadata_extraction_version);
    // "pending" here is the superset of what a call selects - the part waiting for its connector
    // run is reported separately, so the display can explain a remainder the run cannot reach.
    Map<UUID, MetadataBackfillProgress> metadataByLibrary =
        metadataBackfillService.progressForLibraries(libraryIds);
    Map<UUID, ModelExtractionStats> modelExtractionByLibrary =
        modelExtractionCounters.statsFor(libraryIds);
    Map<UUID, ContextPrefixRerunProgress> contextPrefixByLibrary =
        contextPrefixRerunService.progressForLibraries(libraryIds);
    Map<UUID, LibraryMetadataSchemaChangeProgress> schemaChangesByLibrary =
        schemaChangeService.progressForLibraries(libraryIds);

    List<LibrarySearchStatus> result = new ArrayList<>();
    for (KnowledgeLibrary library : libraries) {
      LibraryDocumentStats stats =
          statsByLibrary.getOrDefault(library.getId(), LibraryDocumentStats.empty(library.getId()));
      FullTextIndexFillState fillState =
          fillStateByLibrary.getOrDefault(
              library.getId(), new FullTextIndexFillState(library.getId(), 0, 0, 0));
      MetadataBackfillProgress metadataBackfill =
          metadataByLibrary.getOrDefault(
              library.getId(), MetadataBackfillProgress.empty(library.getId()));
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
              fillState.totalChunks(),
              stats.lastIndexedAt(),
              fillState.indexedChunks(),
              fillState.outdatedChunks(),
              metadataBackfill,
              modelExtractionByLibrary.getOrDefault(
                  library.getId(), ModelExtractionStats.empty(library.getId())),
              contextPrefixByLibrary.getOrDefault(
                  library.getId(), ContextPrefixRerunProgress.empty(library.getId())),
              schemaChangesByLibrary.getOrDefault(
                  library.getId(), LibraryMetadataSchemaChangeProgress.empty(library.getId()))));
    }
    result.sort(
        Comparator.comparing(LibrarySearchStatus::libraryName, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(result);
  }
}
