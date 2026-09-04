package io.opaa.searchadmin;

import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService;
import io.opaa.diagnosticaccess.ForeignDiagnosticContext;
import io.opaa.diagnosticaccess.ForeignDiagnosticContextService;
import io.opaa.diagnosticaccess.ForeignDiagnosticFindings;
import io.opaa.diagnosticaccess.ForeignDiagnosticRequest;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.llm.RerankModelRole;
import io.opaa.query.CandidateOutcome;
import io.opaa.query.CandidateVerdict;
import io.opaa.query.QueryProperties;
import io.opaa.query.RerankAvailability;
import io.opaa.query.RetrievalContext;
import io.opaa.query.RetrievalPipeline;
import io.opaa.query.RetrievalPipelineResult;
import io.opaa.query.SearchedLibraryRef;
import io.opaa.query.StageExplanation;
import java.time.Clock;
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
import org.springframework.stereotype.Service;

/**
 * Runs one test question through the real retrieval pipeline and hands on its explanation protocol
 * (docs/features/hybrid-retrieval.md, "Das Diagnosewerkzeug").
 *
 * <p>Three properties of this service are Baubedingung, not Ausbaustufe:
 *
 * <ul>
 *   <li><b>No search without a permission filter.</b> The scope is resolved through {@link
 *       LibraryAccessService} exactly as a chat query resolves it, then handed to the pipeline,
 *       which turns it into the {@code library_id} filter of every search stage. The diagnosis sets
 *       that filter differently from a chat query; it never omits it (ADR-0008 §5).
 *   <li><b>No chat is ever read.</b> The pipeline runs with an empty conversation history - there
 *       is no parameter on this service that could name an existing conversation.
 *   <li><b>The same retrieval a chat query runs.</b> Every parameter that decides what the pipeline
 *       does is read from the same source the chat path reads it from - the production {@link
 *       QueryProperties} and the rerank model role's current state. A diagnosis that differed from
 *       the real search in even one stage would answer "why these findings?" about findings no user
 *       ever got.
 *   <li><b>No reconstruction.</b> {@link RetrievalPipelineResult#explanation()} is passed through
 *       unchanged. Nothing here re-derives what a stage decided.
 * </ul>
 *
 * <p>Retrieval only: no answer is generated, so the run costs no answer-generation call and cannot
 * put a model's wording between the operator and what the search actually did.
 */
@Service
public class SearchDiagnosisService {

  private final RetrievalPipeline retrievalPipeline;
  private final QueryProperties queryProperties;
  private final LibraryAccessService libraryAccessService;
  private final KnowledgeLibraryRepository libraryRepository;
  private final GroupService groupService;
  private final DocumentRepository documentRepository;
  private final RerankModelRole rerankModelRole;
  private final ForeignDiagnosticContextService foreignDiagnosticContextService;
  private final DiagnosticImpersonationGrantService grantService;
  private final Clock clock;

  public SearchDiagnosisService(
      RetrievalPipeline retrievalPipeline,
      QueryProperties queryProperties,
      LibraryAccessService libraryAccessService,
      KnowledgeLibraryRepository libraryRepository,
      GroupService groupService,
      DocumentRepository documentRepository,
      RerankModelRole rerankModelRole,
      ForeignDiagnosticContextService foreignDiagnosticContextService,
      DiagnosticImpersonationGrantService grantService,
      Clock clock) {
    this.retrievalPipeline = retrievalPipeline;
    this.queryProperties = queryProperties;
    this.libraryAccessService = libraryAccessService;
    this.libraryRepository = libraryRepository;
    this.groupService = groupService;
    this.documentRepository = documentRepository;
    this.rerankModelRole = rerankModelRole;
    this.foreignDiagnosticContextService = foreignDiagnosticContextService;
    this.grantService = grantService;
    this.clock = clock;
  }

  /** The profiles a diagnosis can be run in: the caller's organization's groups, by name. */
  public List<PermissionProfile> permissionProfiles(CurrentUser caller) {
    List<io.opaa.group.Group> groups = groupService.listGroups(caller);
    Map<UUID, Integer> counts =
        libraryAccessService.readableLibraryCountsForGroups(
            groups.stream().map(io.opaa.group.Group::getId).toList(), caller.organizationId());
    return groups.stream()
        .map(
            group ->
                new PermissionProfile(
                    group.getId(), group.getName(), counts.getOrDefault(group.getId(), 0)))
        .sorted(Comparator.comparing(PermissionProfile::name, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * The contexts this caller can choose between: the organization's profiles, and whether they
   * personally hold the "Sicht als" befugnis. That permission is read here only so the page can
   * explain the choice; every run enforces it again inside {@link ForeignDiagnosticContextService},
   * so a client ignoring this answer gains nothing.
   */
  public DiagnosisContextOptions diagnosisContext(CurrentUser caller) {
    return new DiagnosisContextOptions(
        permissionProfiles(caller), grantService.holdsImpersonationPermission(caller));
  }

  /**
   * Runs {@code query} and returns the protocol of what happened.
   *
   * @throws ValidationException when the context type and the profile id contradict each other
   */
  public SearchDiagnosis diagnose(CurrentUser caller, DiagnosisQuery query) {
    return switch (query.contextType()) {
      case USER -> diagnoseAsPerson(caller, query);
      case PERMISSION_PROFILE -> {
        if (query.permissionProfileId() == null) {
          throw new ValidationException(
              "Für eine Diagnose als Rechteprofil ist ein Profil zu wählen.");
        }
        GroupDetail profile = groupService.getGroup(query.permissionProfileId(), caller);
        yield run(
            caller,
            query,
            libraryAccessService.readableLibraryIdsForGroup(
                query.permissionProfileId(), caller.organizationId()),
            profile.group().getName(),
            0);
      }
      case SELF -> {
        if (query.permissionProfileId() != null) {
          throw new ValidationException(
              "Eine Diagnose im eigenen Rechtekontext nimmt kein Rechteprofil entgegen.");
        }
        yield run(
            caller,
            query,
            libraryAccessService.readableLibraryIds(caller.id(), caller.organizationId()),
            null,
            0);
      }
    };
  }

  /**
   * The person context, and the only place it is produced: the search scope comes from {@link
   * ForeignDiagnosticContextService#execute} instead of from this service's own resolution, so
   * befugnis, mandatory justification, subtraction of diagnosegesperrte libraries and the protocol
   * entry cannot be reached around (Leitplanken (c)-(f) and the Klarstellung zu (e) in
   * docs/features/hybrid-retrieval.md). The result is handed back to the caller and stored nowhere
   * (Leitplanke (j)).
   */
  private SearchDiagnosis diagnoseAsPerson(CurrentUser caller, DiagnosisQuery query) {
    if (query.permissionProfileId() != null) {
      throw new ValidationException(
          "Eine Diagnose im Rechtekontext einer Person nimmt kein Rechteprofil entgegen.");
    }
    return foreignDiagnosticContextService
        .execute(
            caller,
            ForeignDiagnosticRequest.forUser(
                query.targetUserId(), query.question(), query.justification()),
            context -> findings(caller, query, context))
        .presentation();
  }

  private ForeignDiagnosticFindings<SearchDiagnosis> findings(
      CurrentUser caller, DiagnosisQuery query, ForeignDiagnosticContext context) {
    SearchDiagnosis diagnosis =
        run(caller, query, context.searchableLibraryIds(), null, context.lockedLibraryIds().size());
    return new ForeignDiagnosticFindings<>(
        diagnosis.selection().stream().map(SearchDiagnosis.SelectedChunk::chunkId).toList(),
        diagnosis);
  }

  private SearchDiagnosis run(
      CurrentUser caller,
      DiagnosisQuery query,
      Set<UUID> searchScope,
      String profileName,
      int lockedLibraryCount) {
    // Empty history, deliberately: the diagnosis never reads a conversation (Leitplanke (a)).
    // The rerank role's state is read exactly as QueryService reads it, so this run reranks
    // whenever a chat query would.
    RetrievalPipelineResult result =
        retrievalPipeline.run(
            new RetrievalContext(
                query.question(),
                List.of(),
                searchScope,
                queryProperties,
                RerankAvailability.of(rerankModelRole.currentStatus().state())));

    Map<String, String> documentKeyByChunkId = documentKeyByChunkId(result);
    List<SearchDiagnosis.SelectedChunk> selection = selection(result, documentKeyByChunkId);
    Map<String, DocumentDescriptor> documentsByKey =
        describeDocuments(documentKeyByChunkId.values());

    TrackedDocumentVerdict tracked =
        query.trackedDocumentId() == null
            ? null
            : trackDocument(caller, query.trackedDocumentId(), searchScope, result, selection);

    return new SearchDiagnosis(
        query.question(),
        query.contextType(),
        profileName,
        clock.instant(),
        searchedLibraries(searchScope),
        result.searchQueries(),
        result.explanation(),
        selection,
        documentsByKey,
        lockedLibraryCount,
        tracked);
  }

  /**
   * Every chunk the run ever held, with the document it belongs to - taken from the verdicts
   * themselves rather than recomputed from chunk metadata, so this mapping cannot disagree with the
   * grouping the pipeline actually used.
   */
  private static Map<String, String> documentKeyByChunkId(RetrievalPipelineResult result) {
    Map<String, String> byChunkId = new HashMap<>();
    for (StageExplanation stage : result.explanation().stages()) {
      for (CandidateVerdict verdict : stage.verdicts()) {
        byChunkId.putIfAbsent(verdict.chunkId(), verdict.documentKey());
      }
    }
    return byChunkId;
  }

  private static List<SearchDiagnosis.SelectedChunk> selection(
      RetrievalPipelineResult result, Map<String, String> documentKeyByChunkId) {
    List<SearchDiagnosis.SelectedChunk> selected = new ArrayList<>(result.chunks().size());
    int rank = 1;
    for (org.springframework.ai.document.Document chunk : result.chunks()) {
      selected.add(
          new SearchDiagnosis.SelectedChunk(
              rank++, chunk.getId(), documentKeyByChunkId.get(chunk.getId())));
    }
    return List.copyOf(selected);
  }

  /**
   * Resolves the opaque document keys to file names and library names. A key that no longer names a
   * document row stays in the result with null fields rather than being dropped - see {@link
   * DocumentDescriptor}.
   */
  private Map<String, DocumentDescriptor> describeDocuments(java.util.Collection<String> keys) {
    Set<String> distinctKeys = new LinkedHashSet<>(keys);
    List<UUID> documentIds = new ArrayList<>();
    for (String key : distinctKeys) {
      parseUuid(key).ifPresent(documentIds::add);
    }
    Map<UUID, Document> documents = new HashMap<>();
    documentRepository.findAllById(documentIds).forEach(doc -> documents.put(doc.getId(), doc));
    Map<UUID, String> libraryNames = libraryNames(documents.values());

    Map<String, DocumentDescriptor> byKey = new HashMap<>();
    for (String key : distinctKeys) {
      Document document = parseUuid(key).map(documents::get).orElse(null);
      if (document == null) {
        byKey.put(key, new DocumentDescriptor(key, null, null, null));
      } else {
        byKey.put(
            key,
            new DocumentDescriptor(
                key,
                document.getFileName(),
                document.getLibraryId(),
                libraryNames.get(document.getLibraryId())));
      }
    }
    return Map.copyOf(byKey);
  }

  private Map<UUID, String> libraryNames(java.util.Collection<Document> documents) {
    Set<UUID> libraryIds = new HashSet<>();
    documents.forEach(
        document -> {
          if (document.getLibraryId() != null) {
            libraryIds.add(document.getLibraryId());
          }
        });
    Map<UUID, String> names = new HashMap<>();
    libraryRepository
        .findAllById(libraryIds)
        .forEach(library -> names.put(library.getId(), library.getName()));
    return names;
  }

  /**
   * The verdict on one specific document: outside the scope, never retrieved, displaced at a named
   * stage, or selected. The stage named for a displaced document is the <b>last</b> one that
   * dropped one of its chunks - see {@link TrackedDocumentVerdict}.
   */
  private TrackedDocumentVerdict trackDocument(
      CurrentUser caller,
      UUID documentId,
      Set<UUID> searchScope,
      RetrievalPipelineResult result,
      List<SearchDiagnosis.SelectedChunk> selection) {
    Document document =
        documentRepository
            .findById(documentId)
            .filter(candidate -> caller.organizationId().equals(candidate.getOrganizationId()))
            .orElseThrow(() -> new io.opaa.common.NotFoundException("Dokument nicht gefunden"));
    String libraryName =
        document.getLibraryId() == null
            ? null
            : libraryRepository
                .findById(document.getLibraryId())
                .map(KnowledgeLibrary::getName)
                .orElse(null);
    Set<String> keys = documentKeysOf(document);

    if (document.getLibraryId() == null || !searchScope.contains(document.getLibraryId())) {
      return new TrackedDocumentVerdict(
          documentId,
          document.getFileName(),
          document.getLibraryId(),
          libraryName,
          TrackedDocumentVerdict.Outcome.OUTSIDE_SEARCH_SCOPE,
          null,
          null,
          0,
          0);
    }

    Set<String> retrievedChunkIds = new HashSet<>();
    CandidateVerdict lastDrop = null;
    io.opaa.query.RetrievalStageName lastDropStage = null;
    for (StageExplanation stage : result.explanation().stages()) {
      for (CandidateVerdict verdict : stage.verdicts()) {
        if (!keys.contains(verdict.documentKey())) {
          continue;
        }
        if (verdict.outcome() == CandidateOutcome.ADDED) {
          retrievedChunkIds.add(verdict.chunkId());
        }
        if (verdict.outcome() == CandidateOutcome.DROPPED) {
          lastDrop = verdict;
          lastDropStage = stage.stage();
        }
      }
    }
    long selectedChunks =
        selection.stream().filter(chunk -> keys.contains(chunk.documentKey())).count();

    TrackedDocumentVerdict.Outcome outcome;
    if (selectedChunks > 0) {
      outcome = TrackedDocumentVerdict.Outcome.IN_FINAL_SELECTION;
    } else if (retrievedChunkIds.isEmpty()) {
      outcome = TrackedDocumentVerdict.Outcome.NOT_RETRIEVED;
    } else {
      outcome = TrackedDocumentVerdict.Outcome.DISPLACED;
    }
    boolean displaced = outcome == TrackedDocumentVerdict.Outcome.DISPLACED;
    return new TrackedDocumentVerdict(
        documentId,
        document.getFileName(),
        document.getLibraryId(),
        libraryName,
        outcome,
        displaced ? lastDropStage : null,
        displaced && lastDrop != null ? lastDrop.reason() : null,
        retrievedChunkIds.size(),
        (int) selectedChunks);
  }

  /**
   * The grouping keys a document's chunks can carry: its id for every chunk written since #739, and
   * the file-name fallback for older ones.
   */
  private static Set<String> documentKeysOf(Document document) {
    Set<String> keys = new HashSet<>();
    keys.add(document.getId().toString());
    if (document.getFileName() != null) {
      keys.add("file:" + document.getFileName());
    }
    return keys;
  }

  private List<SearchedLibraryRef> searchedLibraries(Set<UUID> searchScope) {
    List<SearchedLibraryRef> refs = new ArrayList<>();
    libraryRepository
        .findAllById(searchScope)
        .forEach(library -> refs.add(new SearchedLibraryRef(library.getId(), library.getName())));
    refs.sort(Comparator.comparing(SearchedLibraryRef::getName, String.CASE_INSENSITIVE_ORDER));
    return List.copyOf(refs);
  }

  private static Optional<UUID> parseUuid(String value) {
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** A permission profile: a group and how many libraries it may read. Never a person. */
  public record PermissionProfile(UUID id, String name, int libraryCount) {}

  /**
   * What one caller may choose as a diagnosis context: the organization's profiles, and that
   * caller's own "Sicht als" befugnis - which follows from no role.
   */
  public record DiagnosisContextOptions(
      List<PermissionProfile> profiles, boolean personContextAvailable) {}
}
