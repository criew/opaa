package io.opaa.api;

import io.opaa.api.dto.ChunkInspectionResponse;
import io.opaa.api.dto.DiagnosisSelectionEntryResponse;
import io.opaa.api.dto.DocumentChunksResponse;
import io.opaa.api.dto.LibraryIndexState;
import io.opaa.api.dto.LibrarySearchStatusResponse;
import io.opaa.api.dto.RetrievalCandidateOutcome;
import io.opaa.api.dto.RetrievalStage;
import io.opaa.api.dto.RetrievalStageResponse;
import io.opaa.api.dto.RetrievalStageStatus;
import io.opaa.api.dto.RetrievalVerdictReason;
import io.opaa.api.dto.RetrievalVerdictResponse;
import io.opaa.api.dto.SearchDiagnosisContextType;
import io.opaa.api.dto.SearchDiagnosisResponse;
import io.opaa.api.dto.SearchModelRole;
import io.opaa.api.dto.SearchModelRoleState;
import io.opaa.api.dto.SearchModelRoleStatusResponse;
import io.opaa.api.dto.SearchPath;
import io.opaa.api.dto.SearchPathState;
import io.opaa.api.dto.SearchPathStatusResponse;
import io.opaa.api.dto.SearchPermissionProfileResponse;
import io.opaa.api.dto.SearchStatusResponse;
import io.opaa.api.dto.SearchedLibrary;
import io.opaa.api.dto.TrackedDocumentOutcome;
import io.opaa.api.dto.TrackedDocumentResponse;
import io.opaa.query.CandidateOutcome;
import io.opaa.query.CandidateVerdict;
import io.opaa.query.RetrievalStageName;
import io.opaa.query.SearchedLibraryRef;
import io.opaa.query.StageExplanation;
import io.opaa.query.StageStatus;
import io.opaa.query.VerdictReason;
import io.opaa.searchadmin.ChunkInspection;
import io.opaa.searchadmin.DiagnosisContextType;
import io.opaa.searchadmin.DocumentChunks;
import io.opaa.searchadmin.DocumentDescriptor;
import io.opaa.searchadmin.LibrarySearchStatus;
import io.opaa.searchadmin.ModelRole;
import io.opaa.searchadmin.ModelRoleCondition;
import io.opaa.searchadmin.ModelRoleStatus;
import io.opaa.searchadmin.SearchDiagnosis;
import io.opaa.searchadmin.SearchDiagnosisService;
import io.opaa.searchadmin.SearchPathStatus;
import io.opaa.searchadmin.SearchStatus;
import io.opaa.searchadmin.TrackedDocumentVerdict;
import java.util.List;
import java.util.Map;

/**
 * Maps the {@code io.opaa.searchadmin} domain records onto the generated administration DTOs, and
 * is the one place the German wording of a search-path state and of a rights-context label is
 * decided (#860: no domain service knows a DTO type).
 */
final class SearchAdminResponseMapper {

  private SearchAdminResponseMapper() {}

  static SearchStatusResponse toStatusResponse(SearchStatus status) {
    return new SearchStatusResponse(
        status.modelRoles().stream().map(SearchAdminResponseMapper::toModelRoleResponse).toList(),
        status.searchPaths().stream().map(SearchAdminResponseMapper::toSearchPathResponse).toList(),
        status.libraries().stream().map(SearchAdminResponseMapper::toLibraryResponse).toList());
  }

  static List<SearchPermissionProfileResponse> toPermissionProfileResponses(
      List<SearchDiagnosisService.PermissionProfile> profiles) {
    return profiles.stream()
        .map(
            profile ->
                new SearchPermissionProfileResponse(
                    profile.id(), profile.name(), profile.libraryCount()))
        .toList();
  }

  static SearchDiagnosisResponse toDiagnosisResponse(SearchDiagnosis diagnosis) {
    Map<String, DocumentDescriptor> documents = diagnosis.documentsByKey();
    SearchDiagnosisResponse response =
        new SearchDiagnosisResponse(
            diagnosis.question(),
            toContextType(diagnosis.contextType()),
            contextLabel(diagnosis),
            diagnosis.executedAt(),
            diagnosis.searchScope().stream()
                .map(SearchAdminResponseMapper::toSearchedLibrary)
                .toList(),
            diagnosis.searchQueries(),
            diagnosis.explanation().stages().stream()
                .map(stage -> toStageResponse(stage, documents))
                .toList(),
            diagnosis.selection().stream()
                .map(chunk -> toSelectionEntry(chunk, documents))
                .toList());
    if (diagnosis.trackedDocument() != null) {
      response.setTrackedDocument(toTrackedDocument(diagnosis.trackedDocument()));
    }
    return response;
  }

  static ChunkInspectionResponse toChunkResponse(ChunkInspection chunk) {
    return new ChunkInspectionResponse(
            chunk.chunkId(),
            chunk.documentId(),
            chunk.libraryId(),
            chunk.content(),
            new java.util.HashMap<>(chunk.metadata()))
        .documentTitle(chunk.documentTitle())
        .libraryName(chunk.libraryName())
        .chunkIndex(chunk.chunkIndex());
  }

  static DocumentChunksResponse toDocumentChunksResponse(DocumentChunks document) {
    return new DocumentChunksResponse(
            document.documentId(),
            document.libraryId(),
            document.chunkCount(),
            document.chunks().stream().map(SearchAdminResponseMapper::toChunkResponse).toList())
        .documentTitle(document.documentTitle())
        .libraryName(document.libraryName());
  }

  /**
   * The one place a diagnosis run gets its human label. A profile run names the profile; a run in
   * the caller's own rights context says so, since that is the case the Berechtigungs-Leitplanken
   * exempt from every further Befugnis.
   */
  private static String contextLabel(SearchDiagnosis diagnosis) {
    if (diagnosis.contextType() == DiagnosisContextType.PERMISSION_PROFILE) {
      return "Rechteprofil „" + diagnosis.permissionProfileName() + "“";
    }
    return "Eigener Rechtekontext";
  }

  private static SearchModelRoleStatusResponse toModelRoleResponse(ModelRoleStatus status) {
    return new SearchModelRoleStatusResponse(
            toModelRole(status.role()),
            toModelRoleState(status.condition()),
            status.condition().isFault(),
            status.detail())
        .endpoint(status.endpoint())
        .modelIdentifier(status.modelIdentifier());
  }

  private static SearchPathStatusResponse toSearchPathResponse(SearchPathStatus status) {
    return new SearchPathStatusResponse(
        toSearchPath(status.path()),
        toSearchPathState(status.condition()),
        searchPathDetail(status));
  }

  private static String searchPathDetail(SearchPathStatus status) {
    String pathName =
        status.path() == SearchPathStatus.SearchPathName.VECTOR
            ? "Die Vektorsuche"
            : "Die Volltextsuche";
    return switch (status.condition()) {
      case DISABLED -> pathName + " ist abgeschaltet und liefert für keine Frage Treffer.";
      case ACTIVE ->
          pathName
              + " ist aktiv und deckt alle "
              + status.libraryCount()
              + " Bibliotheken mit Inhalt ab.";
      case INCOMPLETE ->
          pathName
              + " ist aktiv, aber noch nicht über den ganzen Bestand aufgebaut: "
              + status.incompleteLibraryCount()
              + " von "
              + status.libraryCount()
              + " Bibliotheken sind unvollständig und werden von diesem Pfad nicht durchsucht.";
    };
  }

  private static LibrarySearchStatusResponse toLibraryResponse(LibrarySearchStatus status) {
    return new LibrarySearchStatusResponse(
            status.libraryId(),
            status.libraryName(),
            status.documentCount(),
            status.indexedDocumentCount(),
            status.pendingDocumentCount(),
            status.failedDocumentCount(),
            status.lowChunkDocumentCount(),
            status.chunkCount(),
            status.vectorChunkCount(),
            toIndexState(status.vectorIndexCondition()),
            toIndexState(status.fullTextIndexCondition()),
            status.fullTextIndexedChunks(),
            status.fullTextMissingChunks(),
            status.fullTextSkippedChunks(),
            MetadataBackfillResponseMapper.toStatusResponse(status.metadataBackfill()))
        .lastIndexedAt(status.lastIndexedAt());
  }

  private static RetrievalStageResponse toStageResponse(
      StageExplanation stage, Map<String, DocumentDescriptor> documents) {
    return new RetrievalStageResponse(
        toStage(stage.stage()),
        toStageStatus(stage.status()),
        stage.incomingCount(),
        stage.outgoingCount(),
        stage.notes(),
        stage.verdicts().stream().map(verdict -> toVerdict(verdict, documents)).toList());
  }

  private static RetrievalVerdictResponse toVerdict(
      CandidateVerdict verdict, Map<String, DocumentDescriptor> documents) {
    DocumentDescriptor descriptor = documents.get(verdict.documentKey());
    return new RetrievalVerdictResponse(
            verdict.chunkId(),
            verdict.documentKey(),
            toOutcome(verdict.outcome()),
            toReason(verdict.reason()))
        .documentTitle(descriptor == null ? null : descriptor.fileName())
        .libraryName(descriptor == null ? null : descriptor.libraryName())
        .listLabel(verdict.listLabel())
        .rank(verdict.rank())
        .value(verdict.value());
  }

  private static DiagnosisSelectionEntryResponse toSelectionEntry(
      SearchDiagnosis.SelectedChunk chunk, Map<String, DocumentDescriptor> documents) {
    DocumentDescriptor descriptor = documents.get(chunk.documentKey());
    return new DiagnosisSelectionEntryResponse(chunk.rank(), chunk.chunkId(), chunk.documentKey())
        .documentTitle(descriptor == null ? null : descriptor.fileName())
        .libraryName(descriptor == null ? null : descriptor.libraryName());
  }

  private static TrackedDocumentResponse toTrackedDocument(TrackedDocumentVerdict verdict) {
    return new TrackedDocumentResponse(
            verdict.documentId(),
            toTrackedOutcome(verdict.outcome()),
            verdict.retrievedChunkCount(),
            verdict.selectedChunkCount())
        .fileName(verdict.fileName())
        .libraryId(verdict.libraryId())
        .libraryName(verdict.libraryName())
        .displacedAtStage(
            verdict.displacedAtStage() == null ? null : toStage(verdict.displacedAtStage()))
        .displacedReason(
            verdict.displacedReason() == null ? null : toReason(verdict.displacedReason()));
  }

  private static SearchedLibrary toSearchedLibrary(SearchedLibraryRef ref) {
    return new SearchedLibrary(ref.getId(), ref.getName());
  }

  private static SearchModelRole toModelRole(ModelRole role) {
    return switch (role) {
      case CHAT -> SearchModelRole.CHAT;
      case EMBEDDING -> SearchModelRole.EMBEDDING;
      case RERANK -> SearchModelRole.RERANK;
    };
  }

  private static SearchModelRoleState toModelRoleState(ModelRoleCondition condition) {
    return switch (condition) {
      case ACTIVE -> SearchModelRoleState.ACTIVE;
      case DISABLED -> SearchModelRoleState.DISABLED;
      case UNCONFIGURED -> SearchModelRoleState.UNCONFIGURED;
      case UNREACHABLE -> SearchModelRoleState.UNREACHABLE;
    };
  }

  private static SearchPath toSearchPath(SearchPathStatus.SearchPathName path) {
    return switch (path) {
      case VECTOR -> SearchPath.VECTOR;
      case FULL_TEXT -> SearchPath.FULL_TEXT;
    };
  }

  private static SearchPathState toSearchPathState(SearchPathStatus.SearchPathCondition condition) {
    return switch (condition) {
      case ACTIVE -> SearchPathState.ACTIVE;
      case DISABLED -> SearchPathState.DISABLED;
      case INCOMPLETE -> SearchPathState.INCOMPLETE;
    };
  }

  private static LibraryIndexState toIndexState(LibrarySearchStatus.IndexCondition condition) {
    return switch (condition) {
      case EMPTY -> LibraryIndexState.EMPTY;
      case READY -> LibraryIndexState.READY;
      case INCOMPLETE -> LibraryIndexState.INCOMPLETE;
    };
  }

  private static SearchDiagnosisContextType toContextType(DiagnosisContextType type) {
    return switch (type) {
      case SELF -> SearchDiagnosisContextType.SELF;
      case PERMISSION_PROFILE -> SearchDiagnosisContextType.PERMISSION_PROFILE;
    };
  }

  private static RetrievalStage toStage(RetrievalStageName stage) {
    return switch (stage) {
      case SEARCH_SCOPE -> RetrievalStage.SEARCH_SCOPE;
      case SUB_QUERY_DECOMPOSITION -> RetrievalStage.SUB_QUERY_DECOMPOSITION;
      case VECTOR_SEARCH -> RetrievalStage.VECTOR_SEARCH;
      case FULL_TEXT_SEARCH -> RetrievalStage.FULL_TEXT_SEARCH;
      case MMR_SELECTION -> RetrievalStage.MMR_SELECTION;
      case RANK_FUSION -> RetrievalStage.RANK_FUSION;
      case RERANK -> RetrievalStage.RERANK;
      case DOCUMENT_COMPLETION -> RetrievalStage.DOCUMENT_COMPLETION;
    };
  }

  private static RetrievalStageStatus toStageStatus(StageStatus status) {
    return switch (status) {
      case EXECUTED -> RetrievalStageStatus.EXECUTED;
      case DISABLED -> RetrievalStageStatus.DISABLED;
      case NOT_REACHED -> RetrievalStageStatus.NOT_REACHED;
      case UNAVAILABLE -> RetrievalStageStatus.UNAVAILABLE;
    };
  }

  private static RetrievalCandidateOutcome toOutcome(CandidateOutcome outcome) {
    return switch (outcome) {
      case ADDED -> RetrievalCandidateOutcome.ADDED;
      case KEPT -> RetrievalCandidateOutcome.KEPT;
      case DROPPED -> RetrievalCandidateOutcome.DROPPED;
    };
  }

  private static RetrievalVerdictReason toReason(VerdictReason reason) {
    return switch (reason) {
      case RETRIEVED_BY_SEARCH -> RetrievalVerdictReason.RETRIEVED_BY_SEARCH;
      case WITHIN_BUDGET -> RetrievalVerdictReason.WITHIN_BUDGET;
      case OUTSIDE_LIST_BUDGET -> RetrievalVerdictReason.OUTSIDE_LIST_BUDGET;
      case OUTSIDE_FUSION_BUDGET -> RetrievalVerdictReason.OUTSIDE_FUSION_BUDGET;
      case OUTSIDE_RERANK_BUDGET -> RetrievalVerdictReason.OUTSIDE_RERANK_BUDGET;
      case COMPLETED_AS_SIBLING -> RetrievalVerdictReason.COMPLETED_AS_SIBLING;
      case EVICTED_BY_DOCUMENT_COMPLETION_TIER_1 ->
          RetrievalVerdictReason.EVICTED_BY_DOCUMENT_COMPLETION_TIER_1;
      case EVICTED_BY_DOCUMENT_COMPLETION_TIER_2 ->
          RetrievalVerdictReason.EVICTED_BY_DOCUMENT_COMPLETION_TIER_2;
    };
  }

  private static TrackedDocumentOutcome toTrackedOutcome(TrackedDocumentVerdict.Outcome outcome) {
    return switch (outcome) {
      case OUTSIDE_SEARCH_SCOPE -> TrackedDocumentOutcome.OUTSIDE_SEARCH_SCOPE;
      case NOT_RETRIEVED -> TrackedDocumentOutcome.NOT_RETRIEVED;
      case DISPLACED -> TrackedDocumentOutcome.DISPLACED;
      case IN_FINAL_SELECTION -> TrackedDocumentOutcome.IN_FINAL_SELECTION;
    };
  }
}
