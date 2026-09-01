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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * library. A second count with its own logic could show "vollständig" while the gate still refuses
 * the library, which is precisely the confusion this page exists to end.
 */
@Service
public class SearchStatusService {

  private static final Logger log = LoggerFactory.getLogger(SearchStatusService.class);

  /** Sent to the embedding endpoint purely to see whether it answers. */
  private static final String REACHABILITY_PROBE_TEXT = "Erreichbarkeitspruefung";

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
      RetrievalPipelineProperties pipelineProperties) {
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
  }

  /** The whole status display for {@code organizationId}, libraries by name. */
  public SearchStatus statusForOrganization(UUID organizationId) {
    List<LibrarySearchStatus> libraries = libraryStatus(organizationId);
    return new SearchStatus(modelRoles(), searchPaths(libraries), libraries);
  }

  private List<ModelRoleStatus> modelRoles() {
    return List.of(chatRole(), embeddingRole(), rerankRole());
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
  private ModelRoleStatus embeddingRole() {
    EmbeddingInfo info = embeddingInfoService.getEmbeddingInfo();
    try {
      embeddingModel.embed(REACHABILITY_PROBE_TEXT);
      return new ModelRoleStatus(
          ModelRole.EMBEDDING,
          ModelRoleCondition.ACTIVE,
          null,
          info.model(),
          "Das Einbettungsmodell hat auf die Erreichbarkeitsprüfung geantwortet.");
    } catch (RuntimeException e) {
      log.warn("Embedding role reachability probe failed: {}", e.getMessage());
      return new ModelRoleStatus(
          ModelRole.EMBEDDING,
          ModelRoleCondition.UNREACHABLE,
          null,
          info.model(),
          "Das Einbettungsmodell hat auf die Erreichbarkeitsprüfung nicht geantwortet. Ohne"
              + " Einbettungen findet die Vektorsuche nichts.");
    }
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
    Map<UUID, FullTextBackfillProgress> progressByLibrary = new HashMap<>();
    for (FullTextBackfillProgress progress :
        fullTextBackfillProgressService.progressForAllLibraries()) {
      progressByLibrary.put(progress.libraryId(), progress);
    }

    List<LibrarySearchStatus> result = new ArrayList<>();
    for (KnowledgeLibrary library : libraryRepository.findByOrganizationId(organizationId)) {
      LibraryDocumentStats stats =
          statsByLibrary.getOrDefault(library.getId(), LibraryDocumentStats.empty(library.getId()));
      FullTextBackfillProgress progress =
          progressByLibrary.getOrDefault(
              library.getId(), new FullTextBackfillProgress(library.getId(), 0, 0, 0));
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
              progress.missingChunks()));
    }
    result.sort(
        Comparator.comparing(LibrarySearchStatus::libraryName, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(result);
  }
}
