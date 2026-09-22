package io.opaa.searchadmin;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService.ImpersonationAvailability;
import io.opaa.diagnosticaccess.ForeignDiagnosticContext;
import io.opaa.diagnosticaccess.ForeignDiagnosticContextService;
import io.opaa.diagnosticaccess.ForeignDiagnosticFindings;
import io.opaa.diagnosticaccess.ForeignDiagnosticRequest;
import io.opaa.diagnosticaccess.LibraryDiagnosticsLockService;
import io.opaa.group.GroupDetail;
import io.opaa.group.GroupService;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.query.RetrievalContextFactory;
import io.opaa.query.SearchedLibraryRef;
import io.opaa.query.retrieval.CandidateOutcome;
import io.opaa.query.retrieval.CandidateVerdict;
import io.opaa.query.retrieval.RetrievalPipeline;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import io.opaa.query.retrieval.StageExplanation;
import io.opaa.space.SpaceGroupContext;
import io.opaa.space.SpaceService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *       that filter differently from a chat query; it never omits it
 *       (docs/features/spaces-and-assets.md#durchsetzung-zur-abfragezeit).
 *   <li><b>No chat is ever read.</b> The pipeline runs with an empty conversation history - there
 *       is no parameter on this service that could name an existing conversation.
 *   <li><b>The same retrieval a chat query runs.</b> The context comes from the same {@link
 *       RetrievalContextFactory} the chat path uses - production parameters and the rerank model
 *       role's current state. A diagnosis that differed from the real search in even one stage
 *       would answer "why these findings?" about findings no user ever got.
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
  private final RetrievalContextFactory retrievalContextFactory;
  private final LibraryAccessService libraryAccessService;
  private final KnowledgeLibraryRepository libraryRepository;
  private final GroupService groupService;
  private final DocumentRepository documentRepository;
  private final ForeignDiagnosticContextService foreignDiagnosticContextService;
  private final DiagnosticImpersonationGrantService grantService;
  private final LibraryDiagnosticsLockService lockService;
  private final SpaceService spaceService;
  private final AuditEventRecorder auditEventRecorder;
  private final GroupSizeProperties groupSizeProperties;
  private final Clock clock;

  public SearchDiagnosisService(
      RetrievalPipeline retrievalPipeline,
      RetrievalContextFactory retrievalContextFactory,
      LibraryAccessService libraryAccessService,
      KnowledgeLibraryRepository libraryRepository,
      GroupService groupService,
      DocumentRepository documentRepository,
      ForeignDiagnosticContextService foreignDiagnosticContextService,
      DiagnosticImpersonationGrantService grantService,
      LibraryDiagnosticsLockService lockService,
      SpaceService spaceService,
      AuditEventRecorder auditEventRecorder,
      GroupSizeProperties groupSizeProperties,
      Clock clock) {
    this.retrievalPipeline = retrievalPipeline;
    this.retrievalContextFactory = retrievalContextFactory;
    this.libraryAccessService = libraryAccessService;
    this.libraryRepository = libraryRepository;
    this.groupService = groupService;
    this.documentRepository = documentRepository;
    this.foreignDiagnosticContextService = foreignDiagnosticContextService;
    this.grantService = grantService;
    this.lockService = lockService;
    this.spaceService = spaceService;
    this.auditEventRecorder = auditEventRecorder;
    this.groupSizeProperties = groupSizeProperties;
    this.clock = clock;
  }

  /** The profiles a diagnosis can be run in: the caller's organization's groups, by name. */
  public List<PermissionProfile> permissionProfiles(CurrentUser caller) {
    List<io.opaa.group.Group> groups =
        groupService.listGroups(caller).stream().map(io.opaa.group.GroupOverview::group).toList();
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
        permissionProfiles(caller), grantService.impersonationAvailability(caller));
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
        requireNoTargetUser(query);
        GroupDetail profile = groupService.getGroup(query.permissionProfileId(), caller);
        Set<UUID> scope =
            libraryAccessService.readableLibraryIdsForGroup(
                query.permissionProfileId(), caller.organizationId());
        if (query.spaceId() != null) {
          scope = narrowToSpace(caller, query, scope, profile);
        }
        yield run(caller, query, scope, profile.group().getName(), false);
      }
      case SELF -> {
        if (query.permissionProfileId() != null) {
          throw new ValidationException(
              "Eine Diagnose im eigenen Rechtekontext nimmt kein Rechteprofil entgegen.");
        }
        requireNoSpace(query);
        requireNoTargetUser(query);
        yield run(
            caller,
            query,
            libraryAccessService.readableLibraryIds(caller.id(), caller.organizationId()),
            null,
            false);
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
    requireNoSpace(query);
    return foreignDiagnosticContextService
        .execute(
            caller,
            ForeignDiagnosticRequest.forUser(
                query.targetUserId(), query.question(), query.justification()),
            context -> findings(caller, query, context))
        .presentation();
  }

  /**
   * Only a person context is redacted and lock-aware: for the caller's own context and for a
   * profile, the run shows nothing the executing administrator may not see anyway (Leitplanke (c)),
   * and the Diagnosesperre does not apply to those.
   */
  private static void requireNoTargetUser(DiagnosisQuery query) {
    if (query.targetUserId() != null) {
      throw new ValidationException(
          "Eine Zielperson nimmt nur eine Diagnose im Rechtekontext einer Person entgegen.");
    }
  }

  /** A space context belongs to a profile run alone - see {@link #narrowToSpace}. */
  private static void requireNoSpace(DiagnosisQuery query) {
    if (query.spaceId() != null) {
      throw new ValidationException(
          "Ein Space-Kontext ist nur für eine Diagnose als Rechteprofil vorgesehen.");
    }
  }

  /**
   * The Suchbereich of a profile run in a space, and the protection that makes it admissible at all
   * (#1835, ADR-0036 Entscheidung 7): the intersection of the space's libraries with the ones the
   * profile may read, but only once the group reaches the space with at least {@link
   * GroupSizeProperties#minimumGroupSize()} active accounts. The check runs <b>at the moment of the
   * run</b>, not at selection: a group that was big enough yesterday may be a single person today,
   * and a profile that names one person is a person context without its Vollmacht. Only a run that
   * passes is protocolled - one entry, the group id as target, no person anywhere in it.
   */
  private Set<UUID> narrowToSpace(
      CurrentUser caller, DiagnosisQuery query, Set<UUID> readable, GroupDetail profile) {
    SpaceGroupContext context =
        spaceService.spaceGroupContext(query.spaceId(), query.permissionProfileId(), caller);
    if (context.activeMembersWithSpaceAccess() < groupSizeProperties.minimumGroupSize()) {
      throw new AccessDeniedException(
          "Aus diesem Rechteprofil erreichen zu wenige aktive Konten diesen Space, als dass die"
              + " Sicht noch eine Gruppe wäre. Für diese Frage ist der Rechtekontext einer Person"
              + " mit Vollmacht zu wählen.");
    }
    recordProfileRun(caller, query, profile, context);
    Set<UUID> narrowed = new HashSet<>(readable);
    narrowed.retainAll(context.libraryIds());
    return narrowed;
  }

  /**
   * One entry per run with a space context, never one per query, and never a person: {@code
   * target_ref} carries the group id, so "kein Personenbezug im Protokoll" stays a property of the
   * structure rather than of the payload (ADR-0036, Entscheidung 7).
   */
  private void recordProfileRun(
      CurrentUser caller, DiagnosisQuery query, GroupDetail profile, SpaceGroupContext context) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("profileGroupId", query.permissionProfileId().toString());
    payload.put("profileName", profile.group().getName());
    payload.put("spaceName", context.spaceName());
    auditEventRecorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(caller.organizationId())
            .actor(caller.id())
            .type(AuditEventType.SEARCH_DIAGNOSIS_PROFILE_RUN)
            .object(AuditObjectType.SPACE, context.spaceId(), context.spaceName())
            .subject(AuditSubjectKind.GROUP, query.permissionProfileId())
            .after(payload)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private ForeignDiagnosticFindings<SearchDiagnosis> findings(
      CurrentUser caller, DiagnosisQuery query, ForeignDiagnosticContext context) {
    SearchDiagnosis diagnosis = run(caller, query, context.searchableLibraryIds(), null, true);
    return new ForeignDiagnosticFindings<>(displayedChunkIds(diagnosis), diagnosis);
  }

  /**
   * Every chunk this run put in front of the executing person, not just the Endauswahl: each stage
   * verdict is displayed with its document title and library name, so Leitplanke (f)'s "Zahl und
   * Kennungen der angezeigten Fundstellen" covers them too.
   */
  private static List<String> displayedChunkIds(SearchDiagnosis diagnosis) {
    Set<String> chunkIds = new LinkedHashSet<>();
    diagnosis.selection().forEach(chunk -> chunkIds.add(chunk.chunkId()));
    diagnosis
        .explanation()
        .stages()
        .forEach(stage -> stage.verdicts().forEach(verdict -> chunkIds.add(verdict.chunkId())));
    return List.copyOf(chunkIds);
  }

  private SearchDiagnosis run(
      CurrentUser caller,
      DiagnosisQuery query,
      Set<UUID> searchScope,
      String profileName,
      boolean personContext) {
    // Empty history, deliberately: the diagnosis never reads a conversation (Leitplanke (a)).
    RetrievalPipelineResult result =
        retrievalPipeline.run(
            retrievalContextFactory.contextFor(
                query.question(), List.of(), searchScope, query.metadataFilter()));

    Map<String, String> documentKeyByChunkId = documentKeyByChunkId(result);
    List<SearchDiagnosis.SelectedChunk> selection = selection(result, documentKeyByChunkId);
    Map<String, DocumentDescriptor> documentsByKey =
        describeDocuments(documentKeyByChunkId.values());

    TrackedDocumentVerdict tracked =
        query.trackedDocumentId() == null
            ? null
            : trackDocument(
                caller, query.trackedDocumentId(), searchScope, personContext, result, selection);

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
        personContext ? (int) lockService.countLocked(caller.organizationId()) : 0,
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
   * The verdict on one specific document: in a locked area, outside the scope, never retrieved,
   * displaced at a named stage, or selected. The stage named for a displaced document is the
   * <b>last</b> one that dropped one of its chunks - see {@link TrackedDocumentVerdict}.
   *
   * <p>Resolved in the same rights and lock context the run searched in: a document outside {@code
   * searchScope} is answered without its name and without its library, and a document in a
   * diagnosegesperrte library is told apart from one the rights context may not read - calling the
   * lock a Rechtefrage would be a wrong statement about a person (Leitplanke (e)).
   *
   * <p>{@link TrackedDocumentVerdict.Outcome#IN_LOCKED_AREA} follows from the library's lock state
   * alone, never from the intersection of lock and target rights: an intersection would make the
   * verdict differ between a person who may read the locked library and one who may not, and thus
   * answer a question about that person the diagnosis refuses to answer.
   */
  private TrackedDocumentVerdict trackDocument(
      CurrentUser caller,
      UUID documentId,
      Set<UUID> searchScope,
      boolean personContext,
      RetrievalPipelineResult result,
      List<SearchDiagnosis.SelectedChunk> selection) {
    Document document =
        documentRepository
            .findById(documentId)
            .filter(candidate -> caller.organizationId().equals(candidate.getOrganizationId()))
            .orElseThrow(() -> new io.opaa.common.NotFoundException("Dokument nicht gefunden"));
    UUID libraryId = document.getLibraryId();
    Set<String> keys = documentKeysOf(document);

    if (libraryId == null || !searchScope.contains(libraryId)) {
      TrackedDocumentVerdict.Outcome outcome =
          personContext && lockService.isLocked(libraryId)
              ? TrackedDocumentVerdict.Outcome.IN_LOCKED_AREA
              : TrackedDocumentVerdict.Outcome.OUTSIDE_SEARCH_SCOPE;
      return new TrackedDocumentVerdict(
          documentId,
          personContext ? null : document.getFileName(),
          personContext ? null : libraryId,
          personContext ? null : libraryName(libraryId),
          outcome,
          null,
          null,
          0,
          0);
    }

    Set<String> retrievedChunkIds = new HashSet<>();
    CandidateVerdict lastDrop = null;
    io.opaa.query.retrieval.RetrievalStageName lastDropStage = null;
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
        libraryId,
        libraryName(libraryId),
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

  private String libraryName(UUID libraryId) {
    return libraryId == null
        ? null
        : libraryRepository.findById(libraryId).map(KnowledgeLibrary::getName).orElse(null);
  }

  private List<SearchedLibraryRef> searchedLibraries(Set<UUID> searchScope) {
    List<SearchedLibraryRef> refs = new ArrayList<>();
    libraryRepository
        .findAllById(searchScope)
        .forEach(library -> refs.add(new SearchedLibraryRef(library.getId(), library.getName())));
    refs.sort(Comparator.comparing(SearchedLibraryRef::name, String.CASE_INSENSITIVE_ORDER));
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
   * What one caller may choose as a diagnosis context: the organization's profiles, and the state
   * of that caller's own "Sicht als" befugnis - which follows from no role. Holding one whose scope
   * is currently too small is its own state (#1879): the interface has to say that rather than "Sie
   * halten keine".
   */
  public record DiagnosisContextOptions(
      List<PermissionProfile> profiles, ImpersonationAvailability personContext) {

    public boolean personContextAvailable() {
      return personContext == ImpersonationAvailability.USABLE;
    }
  }
}
