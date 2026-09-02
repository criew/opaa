package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.dto.LibraryIndexState;
import io.opaa.api.dto.RetrievalCandidateOutcome;
import io.opaa.api.dto.RetrievalStage;
import io.opaa.api.dto.RetrievalStageStatus;
import io.opaa.api.dto.RetrievalVerdictReason;
import io.opaa.api.dto.SearchDiagnosisContextType;
import io.opaa.api.dto.SearchDiagnosisResponse;
import io.opaa.api.dto.SearchModelRole;
import io.opaa.api.dto.SearchModelRoleState;
import io.opaa.api.dto.SearchPath;
import io.opaa.api.dto.SearchPathState;
import io.opaa.api.dto.SearchStatusResponse;
import io.opaa.api.dto.TrackedDocumentOutcome;
import io.opaa.query.CandidateOutcome;
import io.opaa.query.CandidateVerdict;
import io.opaa.query.RetrievalExplanation;
import io.opaa.query.RetrievalStageName;
import io.opaa.query.SearchedLibraryRef;
import io.opaa.query.StageExplanation;
import io.opaa.query.StageStatus;
import io.opaa.query.VerdictReason;
import io.opaa.searchadmin.DiagnosisContextType;
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
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins that every field of the administration DTOs is actually filled from the domain records
 * (AGENTS.md, "API &amp; DTO-Konvention"), and that no mapping ever carries an access key.
 */
class SearchAdminResponseMapperTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();

  @Test
  void statusResponseCarriesEveryModelRoleFieldAndNoAccessKey() {
    SearchStatus status =
        new SearchStatus(
            List.of(
                new ModelRoleStatus(
                    ModelRole.CHAT,
                    ModelRoleCondition.ACTIVE,
                    "http://ollama:11434/v1",
                    "qwen3:8b",
                    "Verbindung erfolgreich."),
                new ModelRoleStatus(
                    ModelRole.RERANK, ModelRoleCondition.DISABLED, null, null, "Abgeschaltet.")),
            List.of(),
            List.of());

    SearchStatusResponse response = SearchAdminResponseMapper.toStatusResponse(status);

    var chat = response.getModelRoles().get(0);
    assertThat(chat.getRole()).isEqualTo(SearchModelRole.CHAT);
    assertThat(chat.getState()).isEqualTo(SearchModelRoleState.ACTIVE);
    assertThat(chat.getEndpoint()).isEqualTo("http://ollama:11434/v1");
    assertThat(chat.getModelIdentifier()).isEqualTo("qwen3:8b");
    assertThat(chat.getDetail()).isEqualTo("Verbindung erfolgreich.");
    assertThat(chat.getFaulted()).isFalse();
    assertThat(response.getModelRoles().get(1).getState()).isEqualTo(SearchModelRoleState.DISABLED);
    assertThat(response.getModelRoles().get(1).getFaulted()).isFalse();
    // The DTO has no field an access key could travel in - asserted on the serialized shape so a
    // future schema change that adds one fails here rather than in production.
    assertThat(chat.toString()).doesNotContain("apiKey", "Schlüssel");
  }

  @Test
  void aRoleThatIsSwitchedOnButUnbelegtIsMarkedAsAFault() {
    SearchStatusResponse response =
        SearchAdminResponseMapper.toStatusResponse(
            new SearchStatus(
                List.of(
                    new ModelRoleStatus(
                        ModelRole.RERANK, ModelRoleCondition.UNCONFIGURED, null, null, "Störung.")),
                List.of(),
                List.of()));

    assertThat(response.getModelRoles().get(0).getState())
        .isEqualTo(SearchModelRoleState.UNCONFIGURED);
    assertThat(response.getModelRoles().get(0).getFaulted()).isTrue();
  }

  @Test
  void anIncompleteSearchPathNamesHowManyLibrariesAreMissing() {
    SearchStatusResponse response =
        SearchAdminResponseMapper.toStatusResponse(
            new SearchStatus(
                List.of(),
                List.of(
                    new SearchPathStatus(
                        SearchPathStatus.SearchPathName.FULL_TEXT,
                        SearchPathStatus.SearchPathCondition.INCOMPLETE,
                        2,
                        5)),
                List.of()));

    var path = response.getSearchPaths().get(0);
    assertThat(path.getPath()).isEqualTo(SearchPath.FULL_TEXT);
    assertThat(path.getState()).isEqualTo(SearchPathState.INCOMPLETE);
    assertThat(path.getDetail()).contains("Volltextsuche", "2 von 5");
  }

  @Test
  void libraryStatusCarriesEveryCountAndBothIndexStates() {
    LibrarySearchStatus library =
        new LibrarySearchStatus(
            LIBRARY_ID,
            "Satzungen",
            12,
            10,
            1,
            1,
            3,
            240,
            230,
            Instant.parse("2026-09-01T08:00:00Z"),
            200,
            30,
            0);

    var response =
        SearchAdminResponseMapper.toStatusResponse(
                new SearchStatus(List.of(), List.of(), List.of(library)))
            .getLibraries()
            .get(0);

    assertThat(response.getLibraryId()).isEqualTo(LIBRARY_ID);
    assertThat(response.getLibraryName()).isEqualTo("Satzungen");
    assertThat(response.getDocumentCount()).isEqualTo(12);
    assertThat(response.getIndexedDocumentCount()).isEqualTo(10);
    assertThat(response.getPendingDocumentCount()).isEqualTo(1);
    assertThat(response.getFailedDocumentCount()).isEqualTo(1);
    assertThat(response.getLowChunkDocumentCount()).isEqualTo(3);
    assertThat(response.getChunkCount()).isEqualTo(240);
    assertThat(response.getVectorChunkCount()).isEqualTo(230);
    assertThat(response.getLastIndexedAt()).isEqualTo(Instant.parse("2026-09-01T08:00:00Z"));
    assertThat(response.getFullTextIndexedChunks()).isEqualTo(200);
    assertThat(response.getFullTextMissingChunks()).isEqualTo(30);
    // Pending documents make the vector index incomplete; missing full-text rows do the same for
    // the lexical one - the condition the completion gate reads.
    assertThat(response.getVectorIndexState()).isEqualTo(LibraryIndexState.INCOMPLETE);
    assertThat(response.getFullTextIndexState()).isEqualTo(LibraryIndexState.INCOMPLETE);
  }

  @Test
  void aLibraryWithoutChunksReportsBothIndexesAsEmpty() {
    var response =
        SearchAdminResponseMapper.toStatusResponse(
                new SearchStatus(
                    List.of(),
                    List.of(),
                    List.of(
                        new LibrarySearchStatus(
                            LIBRARY_ID, "Leer", 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0))))
            .getLibraries()
            .get(0);

    assertThat(response.getVectorIndexState()).isEqualTo(LibraryIndexState.EMPTY);
    assertThat(response.getFullTextIndexState()).isEqualTo(LibraryIndexState.EMPTY);
    assertThat(response.getLastIndexedAt()).isNull();
  }

  @Test
  void permissionProfilesCarryIdNameAndLibraryCount() {
    var responses =
        SearchAdminResponseMapper.toPermissionProfileResponses(
            List.of(new SearchDiagnosisService.PermissionProfile(LIBRARY_ID, "Bürgerbüro", 4)));

    assertThat(responses)
        .singleElement()
        .satisfies(
            profile -> {
              assertThat(profile.getId()).isEqualTo(LIBRARY_ID);
              assertThat(profile.getName()).isEqualTo("Bürgerbüro");
              assertThat(profile.getLibraryCount()).isEqualTo(4);
            });
  }

  @Test
  void diagnosisResponseCarriesEveryStageVerdictAndSelectionField() {
    SearchDiagnosisResponse response =
        SearchAdminResponseMapper.toDiagnosisResponse(diagnosis(null));

    assertThat(response.getQuestion()).isEqualTo("Gebührenbefreiung?");
    assertThat(response.getContextType()).isEqualTo(SearchDiagnosisContextType.PERMISSION_PROFILE);
    assertThat(response.getContextLabel()).isEqualTo("Rechteprofil „Bürgerbüro“");
    assertThat(response.getExecutedAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
    assertThat(response.getSearchScope())
        .singleElement()
        .satisfies(
            library -> {
              assertThat(library.getId()).isEqualTo(LIBRARY_ID);
              assertThat(library.getName()).isEqualTo("Satzungen");
            });
    assertThat(response.getSearchQueries()).containsExactly("Gebührenbefreiung Bedürftigkeit");

    assertThat(response.getStages()).hasSize(2);
    var vectorSearch = response.getStages().get(0);
    assertThat(vectorSearch.getStage()).isEqualTo(RetrievalStage.VECTOR_SEARCH);
    assertThat(vectorSearch.getStatus()).isEqualTo(RetrievalStageStatus.EXECUTED);
    assertThat(vectorSearch.getIncomingCount()).isZero();
    assertThat(vectorSearch.getOutgoingCount()).isEqualTo(2);
    assertThat(vectorSearch.getNotes()).containsExactly("25 Kandidaten je Teilfrage");
    var verdict = vectorSearch.getVerdicts().get(0);
    assertThat(verdict.getChunkId()).isEqualTo("chunk-1");
    assertThat(verdict.getDocumentKey()).isEqualTo(DOCUMENT_ID.toString());
    assertThat(verdict.getDocumentTitle()).isEqualTo("satzung.pdf");
    assertThat(verdict.getLibraryName()).isEqualTo("Satzungen");
    assertThat(verdict.getOutcome()).isEqualTo(RetrievalCandidateOutcome.ADDED);
    assertThat(verdict.getReason()).isEqualTo(RetrievalVerdictReason.RETRIEVED_BY_SEARCH);
    assertThat(verdict.getListLabel()).isEqualTo("vector#1");
    assertThat(verdict.getRank()).isEqualTo(1);
    assertThat(verdict.getValue()).isEqualTo(0.82);

    assertThat(response.getStages().get(1).getStatus()).isEqualTo(RetrievalStageStatus.DISABLED);

    assertThat(response.getFinalSelection())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.getRank()).isEqualTo(1);
              assertThat(entry.getChunkId()).isEqualTo("chunk-1");
              assertThat(entry.getDocumentKey()).isEqualTo(DOCUMENT_ID.toString());
              assertThat(entry.getDocumentTitle()).isEqualTo("satzung.pdf");
              assertThat(entry.getLibraryName()).isEqualTo("Satzungen");
            });
    assertThat(response.getTrackedDocument()).isNull();
  }

  @Test
  void aRunInTheCallersOwnContextSaysSoInsteadOfNamingAProfile() {
    SearchDiagnosis ownContext =
        new SearchDiagnosis(
            "Frage",
            DiagnosisContextType.SELF,
            null,
            Instant.parse("2026-09-01T10:00:00Z"),
            List.of(),
            List.of(),
            new RetrievalExplanation(List.of()),
            List.of(),
            Map.of(),
            null);

    assertThat(SearchAdminResponseMapper.toDiagnosisResponse(ownContext).getContextLabel())
        .isEqualTo("Eigener Rechtekontext");
  }

  @Test
  void aDisplacedDocumentNamesTheStageAndReasonItWasLostAt() {
    var tracked =
        SearchAdminResponseMapper.toDiagnosisResponse(
                diagnosis(
                    new TrackedDocumentVerdict(
                        DOCUMENT_ID,
                        "satzung.pdf",
                        LIBRARY_ID,
                        "Satzungen",
                        TrackedDocumentVerdict.Outcome.DISPLACED,
                        RetrievalStageName.RANK_FUSION,
                        VerdictReason.OUTSIDE_FUSION_BUDGET,
                        3,
                        0)))
            .getTrackedDocument();

    assertThat(tracked.getDocumentId()).isEqualTo(DOCUMENT_ID);
    assertThat(tracked.getFileName()).isEqualTo("satzung.pdf");
    assertThat(tracked.getLibraryId()).isEqualTo(LIBRARY_ID);
    assertThat(tracked.getLibraryName()).isEqualTo("Satzungen");
    assertThat(tracked.getOutcome()).isEqualTo(TrackedDocumentOutcome.DISPLACED);
    assertThat(tracked.getDisplacedAtStage()).isEqualTo(RetrievalStage.RANK_FUSION);
    assertThat(tracked.getDisplacedReason())
        .isEqualTo(RetrievalVerdictReason.OUTSIDE_FUSION_BUDGET);
    assertThat(tracked.getRetrievedChunkCount()).isEqualTo(3);
    assertThat(tracked.getSelectedChunkCount()).isZero();
  }

  @Test
  void aDocumentNoSearchStageFoundIsDistinguishableFromADisplacedOne() {
    var tracked =
        SearchAdminResponseMapper.toDiagnosisResponse(
                diagnosis(
                    new TrackedDocumentVerdict(
                        DOCUMENT_ID,
                        "satzung.pdf",
                        LIBRARY_ID,
                        "Satzungen",
                        TrackedDocumentVerdict.Outcome.NOT_RETRIEVED,
                        null,
                        null,
                        0,
                        0)))
            .getTrackedDocument();

    assertThat(tracked.getOutcome()).isEqualTo(TrackedDocumentOutcome.NOT_RETRIEVED);
    assertThat(tracked.getDisplacedAtStage()).isNull();
    assertThat(tracked.getDisplacedReason()).isNull();
  }

  /**
   * The stage list an operator actually sees must be the stage list the run produced - one entry
   * per stage, in order, nothing dropped on the way through the mapper. The service-level test of
   * the same equality cannot catch a reduction that happens here.
   */
  @Test
  void everyStageOfTheRunSurvivesIntoTheResponse() {
    List<StageExplanation> everyStage =
        Arrays.stream(RetrievalStageName.values())
            .map(
                stage ->
                    new StageExplanation(stage, StageStatus.EXECUTED, 1, 1, List.of(), List.of()))
            .toList();
    SearchDiagnosis fullRun = withStages(everyStage);

    SearchDiagnosisResponse response = SearchAdminResponseMapper.toDiagnosisResponse(fullRun);

    assertThat(response.getStages())
        .extracting(stage -> stage.getStage().name())
        .containsExactlyElementsOf(
            fullRun.explanation().stages().stream().map(stage -> stage.stage().name()).toList());
    assertThat(response.getStages()).hasSameSizeAs(RetrievalStageName.values());
  }

  private static SearchDiagnosis withStages(List<StageExplanation> stages) {
    SearchDiagnosis base = diagnosis(null);
    return new SearchDiagnosis(
        base.question(),
        base.contextType(),
        base.permissionProfileName(),
        base.executedAt(),
        base.searchScope(),
        base.searchQueries(),
        new RetrievalExplanation(stages),
        base.selection(),
        base.documentsByKey(),
        base.trackedDocument());
  }

  private static SearchDiagnosis diagnosis(TrackedDocumentVerdict tracked) {
    StageExplanation vectorSearch =
        new StageExplanation(
            RetrievalStageName.VECTOR_SEARCH,
            StageStatus.EXECUTED,
            0,
            2,
            List.of(
                new CandidateVerdict(
                    "chunk-1",
                    DOCUMENT_ID.toString(),
                    CandidateOutcome.ADDED,
                    VerdictReason.RETRIEVED_BY_SEARCH,
                    "vector#1",
                    1,
                    0.82),
                new CandidateVerdict(
                    "chunk-2",
                    "file:unbekannt.pdf",
                    CandidateOutcome.ADDED,
                    VerdictReason.RETRIEVED_BY_SEARCH,
                    "vector#1",
                    2,
                    0.71)),
            List.of("25 Kandidaten je Teilfrage"));
    StageExplanation rerankPlaceholder =
        new StageExplanation(
            RetrievalStageName.MMR_SELECTION, StageStatus.DISABLED, 2, 2, List.of(), List.of());

    return new SearchDiagnosis(
        "Gebührenbefreiung?",
        DiagnosisContextType.PERMISSION_PROFILE,
        "Bürgerbüro",
        Instant.parse("2026-09-01T10:00:00Z"),
        List.of(new SearchedLibraryRef(LIBRARY_ID, "Satzungen")),
        List.of("Gebührenbefreiung Bedürftigkeit"),
        new RetrievalExplanation(List.of(vectorSearch, rerankPlaceholder)),
        List.of(new SearchDiagnosis.SelectedChunk(1, "chunk-1", DOCUMENT_ID.toString())),
        Map.of(
            DOCUMENT_ID.toString(),
            new DocumentDescriptor(DOCUMENT_ID.toString(), "satzung.pdf", LIBRARY_ID, "Satzungen")),
        tracked);
  }
}
