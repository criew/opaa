package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.query.CandidateOutcome;
import io.opaa.query.RetrievalPipeline;
import io.opaa.query.RetrievalStageName;
import io.opaa.query.StageExplanation;
import io.opaa.query.StageStatus;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The diagnosis against a real Postgres and a real pipeline run - the acceptance criteria of #1053
 * that cannot be shown on mocks: that the stage view comes from the pipeline's own explanation
 * protocol, that the run applies a permission filter it never widens, and that a document outside
 * the Endauswahl is distinguishable as "never found" from "displaced at a stage".
 */
@OpaaIndexingIntegrationTest
class SearchDiagnosisIntegrationTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  /** Comfortably above {@code opaa.query.top-k} (8), so a run must displace candidates. */
  private static final int DOCUMENT_COUNT = 15;

  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SearchDiagnosisService diagnosisService;
  @Autowired private RetrievalPipeline retrievalPipeline;

  private UUID adminId;
  private UUID grantedLibraryId;
  private UUID ungrantedLibraryId;
  private UUID profileGroupId;
  private final List<UUID> documentIds = new ArrayList<>();
  private UUID documentWithoutChunks;
  private UUID documentInUngrantedLibrary;

  private CurrentUser admin() {
    return CurrentUser.of(adminId, DEFAULT_ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN, null);
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    adminId = UUID.randomUUID();
    grantedLibraryId = UUID.randomUUID();
    ungrantedLibraryId = UUID.randomUUID();
    profileGroupId = UUID.randomUUID();
    documentIds.clear();

    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Diagnose-Admin', now(),"
            + " 'SYSTEM_ADMIN', ?)",
        adminId,
        "diagnosis-it-" + adminId,
        "diagnosis-it-" + adminId + "@example.com",
        DEFAULT_ORGANIZATION_ID);
    insertLibrary(grantedLibraryId, "Satzungen & Gebührenordnungen");
    insertLibrary(ungrantedLibraryId, "Personalvorgänge");
    jdbcTemplate.update(
        "INSERT INTO groups (id, organization_id, kind, name, created_at, updated_at, dissolved)"
            + " VALUES (?, ?, 'AD_HOC', 'Sachbearbeitung Bürgerbüro', now(), now(), false)",
        profileGroupId,
        DEFAULT_ORGANIZATION_ID);
    // The profile reaches exactly one of the two libraries - the whole point of the scope tests.
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type, subject_group_id,"
            + " role, created_at, updated_at) VALUES (?, ?, ?, 'GROUP', ?, 'VIEWER', now(), now())",
        UUID.randomUUID(),
        grantedLibraryId,
        DEFAULT_ORGANIZATION_ID,
        profileGroupId);
    // The acting admin reaches both, directly - so an "own context" run is genuinely wider.
    grantToAdmin(grantedLibraryId);
    grantToAdmin(ungrantedLibraryId);

    List<Document> chunks = new ArrayList<>();
    for (int i = 0; i < DOCUMENT_COUNT; i++) {
      UUID documentId = UUID.randomUUID();
      documentIds.add(documentId);
      insertDocument(documentId, grantedLibraryId, "satzung-" + i + ".pdf", 1);
      chunks.add(chunk(documentId, grantedLibraryId, "satzung-" + i + ".pdf"));
    }
    documentWithoutChunks = UUID.randomUUID();
    insertDocument(documentWithoutChunks, grantedLibraryId, "scan-ohne-textebene.pdf", 0);
    documentInUngrantedLibrary = UUID.randomUUID();
    insertDocument(documentInUngrantedLibrary, ungrantedLibraryId, "personalakte.pdf", 3);
    vectorStore.add(chunks);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM documents WHERE library_id in (?, ?)", grantedLibraryId, ungrantedLibraryId);
    jdbcTemplate.update(
        "DELETE FROM asset_grants WHERE library_id in (?, ?)",
        grantedLibraryId,
        ungrantedLibraryId);
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE id in (?, ?)", grantedLibraryId, ungrantedLibraryId);
    jdbcTemplate.update("DELETE FROM groups WHERE id = ?", profileGroupId);
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", adminId);
  }

  @Test
  void everyRegisteredStageAppearsInTheProtocolTheRunItselfProduced() {
    SearchDiagnosis diagnosis =
        diagnose(profileQuery("Gebührenbefreiung wegen Bedürftigkeit", null));

    assertThat(diagnosis.explanation().stages().stream().map(StageExplanation::stage).toList())
        .isEqualTo(retrievalPipeline.registeredStages());
    assertThat(diagnosis.explanation().stages())
        .anySatisfy(
            stage -> {
              assertThat(stage.stage()).isEqualTo(RetrievalStageName.VECTOR_SEARCH);
              assertThat(stage.status()).isEqualTo(StageStatus.EXECUTED);
              assertThat(stage.verdicts()).isNotEmpty();
            });
    assertThat(diagnosis.searchQueries()).isNotEmpty();
    assertThat(diagnosis.selection()).isNotEmpty();
  }

  @Test
  void aProfileRunSearchesOnlyTheLibrariesThatProfileMayRead() {
    SearchDiagnosis diagnosis = diagnose(profileQuery("Gebührenbefreiung", null));

    assertThat(diagnosis.searchScope())
        .extracting(ref -> ref.getId())
        .containsExactly(grantedLibraryId);
    assertThat(diagnosis.permissionProfileName()).isEqualTo("Sachbearbeitung Bürgerbüro");
    // Every chunk the run ever saw belongs to the one library the profile reaches - the filter is
    // applied inside the search, not afterwards.
    assertThat(retrievedDocumentKeys(diagnosis))
        .allSatisfy(key -> assertThat(documentIds).contains(UUID.fromString(key)));
  }

  @Test
  void anOwnContextRunSearchesEveryLibraryTheAdministratorMayRead() {
    SearchDiagnosis diagnosis =
        diagnose(new DiagnosisQuery("Gebührenbefreiung", DiagnosisContextType.SELF, null, null));

    assertThat(diagnosis.searchScope())
        .extracting(ref -> ref.getId())
        .containsExactlyInAnyOrder(grantedLibraryId, ungrantedLibraryId);
    assertThat(diagnosis.permissionProfileName()).isNull();
  }

  @Test
  void aDocumentOutsideTheProfilesScopeIsReportedAsSuchRatherThanAsNotFound() {
    SearchDiagnosis diagnosis =
        diagnose(profileQuery("Gebührenbefreiung", documentInUngrantedLibrary));

    assertThat(diagnosis.trackedDocument().outcome())
        .isEqualTo(TrackedDocumentVerdict.Outcome.OUTSIDE_SEARCH_SCOPE);
    assertThat(diagnosis.trackedDocument().fileName()).isEqualTo("personalakte.pdf");
    assertThat(diagnosis.trackedDocument().libraryName()).isEqualTo("Personalvorgänge");
  }

  @Test
  void aDocumentNoSearchStageFoundIsReportedAsNotRetrieved() {
    SearchDiagnosis diagnosis = diagnose(profileQuery("Gebührenbefreiung", documentWithoutChunks));

    // Inside the scope, but no chunk of it exists - the indexing problem, not the ranking problem.
    assertThat(diagnosis.trackedDocument().outcome())
        .isEqualTo(TrackedDocumentVerdict.Outcome.NOT_RETRIEVED);
    assertThat(diagnosis.trackedDocument().retrievedChunkCount()).isZero();
    assertThat(diagnosis.trackedDocument().displacedAtStage()).isNull();
  }

  @Test
  void aDocumentFoundButNotSelectedNamesTheStageThatDisplacedIt() {
    SearchDiagnosis probe = diagnose(profileQuery("Gebührenbefreiung", null));
    Set<String> selectedKeys =
        probe.selection().stream()
            .map(SearchDiagnosis.SelectedChunk::documentKey)
            .collect(java.util.stream.Collectors.toSet());
    String displacedKey =
        retrievedDocumentKeys(probe).stream()
            .filter(key -> !selectedKeys.contains(key))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "no candidate was displaced - the fixture must exceed top-k"));

    SearchDiagnosis diagnosis =
        diagnose(profileQuery("Gebührenbefreiung", UUID.fromString(displacedKey)));

    assertThat(diagnosis.trackedDocument().outcome())
        .isEqualTo(TrackedDocumentVerdict.Outcome.DISPLACED);
    assertThat(diagnosis.trackedDocument().retrievedChunkCount()).isPositive();
    assertThat(diagnosis.trackedDocument().selectedChunkCount()).isZero();
    assertThat(diagnosis.trackedDocument().displacedAtStage()).isNotNull();
    assertThat(diagnosis.trackedDocument().displacedReason()).isNotNull();
  }

  @Test
  void aSelectedDocumentIsReportedAsBeingInTheFinalSelection() {
    SearchDiagnosis probe = diagnose(profileQuery("Gebührenbefreiung", null));
    UUID selected = UUID.fromString(probe.selection().get(0).documentKey());

    SearchDiagnosis diagnosis = diagnose(profileQuery("Gebührenbefreiung", selected));

    assertThat(diagnosis.trackedDocument().outcome())
        .isEqualTo(TrackedDocumentVerdict.Outcome.IN_FINAL_SELECTION);
    assertThat(diagnosis.trackedDocument().selectedChunkCount()).isPositive();
  }

  @Test
  void everyProtocolEntryResolvesToAReadableDocumentAndLibraryName() {
    SearchDiagnosis diagnosis = diagnose(profileQuery("Gebührenbefreiung", null));

    assertThat(diagnosis.documentsByKey()).isNotEmpty();
    assertThat(diagnosis.documentsByKey().values())
        .allSatisfy(
            descriptor -> {
              assertThat(descriptor.fileName()).startsWith("satzung-");
              assertThat(descriptor.libraryName()).isEqualTo("Satzungen & Gebührenordnungen");
            });
  }

  @Test
  void aProfileRunWithoutAProfileIsRejected() {
    assertThatThrownBy(
            () ->
                diagnose(
                    new DiagnosisQuery(
                        "Gebührenbefreiung", DiagnosisContextType.PERMISSION_PROFILE, null, null)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void permissionProfilesAreListedWithTheirReadableLibraryCount() {
    assertThat(diagnosisService.permissionProfiles(admin()))
        .anySatisfy(
            profile -> {
              assertThat(profile.name()).isEqualTo("Sachbearbeitung Bürgerbüro");
              assertThat(profile.libraryCount()).isEqualTo(1);
            });
  }

  private SearchDiagnosis diagnose(DiagnosisQuery query) {
    return diagnosisService.diagnose(admin(), query);
  }

  private DiagnosisQuery profileQuery(String question, UUID trackedDocumentId) {
    return new DiagnosisQuery(
        question, DiagnosisContextType.PERMISSION_PROFILE, profileGroupId, trackedDocumentId);
  }

  /** Every document key any search stage brought into the run. */
  private static Set<String> retrievedDocumentKeys(SearchDiagnosis diagnosis) {
    Set<String> keys = new HashSet<>();
    diagnosis
        .explanation()
        .stages()
        .forEach(
            stage ->
                stage.verdicts().stream()
                    .filter(verdict -> verdict.outcome() == CandidateOutcome.ADDED)
                    .forEach(verdict -> keys.add(verdict.documentKey())));
    return keys;
  }

  private void insertLibrary(UUID libraryId, String name) {
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', false, 'UPLOAD', now(), now())",
        libraryId,
        DEFAULT_ORGANIZATION_ID,
        name,
        adminId);
  }

  private void grantToAdmin(UUID libraryId) {
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, library_id, organization_id, subject_type, subject_user_id,"
            + " role, created_at, updated_at) VALUES (?, ?, ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        libraryId,
        DEFAULT_ORGANIZATION_ID,
        adminId);
  }

  private void insertDocument(UUID documentId, UUID libraryId, String fileName, int chunkCount) {
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, 'application/pdf', 1024, ?, now(), ?, 'INDEXED', 'UPLOAD', ?, ?,"
            + " now())",
        documentId,
        fileName,
        "diagnosis-it/" + fileName + "/" + documentId,
        chunkCount,
        "checksum-" + documentId,
        libraryId,
        DEFAULT_ORGANIZATION_ID);
  }

  private static Document chunk(UUID documentId, UUID libraryId, String fileName) {
    return new Document(
        "Über die Befreiung von Verwaltungsgebühren wegen Bedürftigkeit entscheidet die Behörde.",
        Map.of(
            "file_name",
            fileName,
            "document_id",
            documentId.toString(),
            "chunk_index",
            0,
            "library_id",
            libraryId.toString()));
  }
}
