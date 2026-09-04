package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.MetadataBackfillProgress;
import io.opaa.query.RetrievalExplanation;
import io.opaa.searchadmin.ChunkInspection;
import io.opaa.searchadmin.ChunkInspectionService;
import io.opaa.searchadmin.DiagnosisContextType;
import io.opaa.searchadmin.DiagnosisQuery;
import io.opaa.searchadmin.DocumentChunks;
import io.opaa.searchadmin.LibrarySearchStatus;
import io.opaa.searchadmin.ModelRole;
import io.opaa.searchadmin.ModelRoleCondition;
import io.opaa.searchadmin.ModelRoleStatus;
import io.opaa.searchadmin.SearchDiagnosis;
import io.opaa.searchadmin.SearchDiagnosisService;
import io.opaa.searchadmin.SearchStatus;
import io.opaa.searchadmin.SearchStatusService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link SearchAdminController} in isolation - proves the {@code SYSTEM_ADMIN} access bar, that the
 * caller's own organization drives the status query, and that the diagnosis takes only a freshly
 * entered question and a permission profile, never a chat and never a person.
 */
@WebMvcTest(SearchAdminController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class SearchAdminControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SearchStatusService searchStatusService;
  @MockitoBean private SearchDiagnosisService searchDiagnosisService;
  @MockitoBean private ChunkInspectionService chunkInspectionService;
  @MockitoBean private UserService userService;

  private final UUID actingAdminId = UUID.randomUUID();
  private final UUID actingAdminOrganizationId = UUID.randomUUID();

  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
  }

  private RequestPostProcessor asRegularUser() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @BeforeEach
  void setUp() {
    User actingAdmin = new User(TEST_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    actingAdmin.setOrganizationId(actingAdminOrganizationId);
    setId(actingAdmin, actingAdminId);
    when(userService.findOrCreateUser(eq(TEST_SUBJECT), eq(TEST_ISSUER), any(), any()))
        .thenReturn(actingAdmin);
  }

  private void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void statusIsNotReachableForARegularUser() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/search/status").with(asRegularUser()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(searchStatusService);
  }

  @Test
  void diagnosisIsNotReachableForARegularUser() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/search/diagnosis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Test\",\"contextType\":\"SELF\"}")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(searchDiagnosisService);
  }

  @Test
  void statusScopesToTheCallersOwnOrganization() throws Exception {
    when(searchStatusService.statusForOrganization(actingAdminOrganizationId))
        .thenReturn(
            new SearchStatus(
                List.of(
                    new ModelRoleStatus(
                        ModelRole.RERANK,
                        ModelRoleCondition.UNCONFIGURED,
                        null,
                        null,
                        "Reranking ist eingeschaltet, aber unbelegt.")),
                List.of(),
                List.of(
                    new LibrarySearchStatus(
                        UUID.randomUUID(),
                        "Satzungen",
                        5,
                        5,
                        0,
                        0,
                        2,
                        100,
                        100,
                        Instant.EPOCH,
                        80,
                        20,
                        0,
                        new MetadataBackfillProgress(
                            UUID.randomUUID(),
                            5,
                            2,
                            3,
                            1,
                            1,
                            Map.of(CoreMetadataField.TITLE, 2L))))));

    mockMvc
        .perform(get("/api/v1/admin/search/status").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.modelRoles[0].role").value("RERANK"))
        .andExpect(jsonPath("$.modelRoles[0].state").value("UNCONFIGURED"))
        .andExpect(jsonPath("$.modelRoles[0].faulted").value(true))
        .andExpect(jsonPath("$.libraries[0].lowChunkDocumentCount").value(2))
        .andExpect(jsonPath("$.libraries[0].fullTextMissingChunks").value(20))
        .andExpect(jsonPath("$.libraries[0].fullTextIndexState").value("INCOMPLETE"))
        .andExpect(jsonPath("$.libraries[0].metadataBackfill.pendingDocuments").value(3))
        .andExpect(
            jsonPath("$.libraries[0].metadataBackfill.awaitingConnectorRunDocuments").value(1))
        .andExpect(jsonPath("$.libraries[0].metadataBackfill.lastSkippedDocuments").value(1))
        .andExpect(jsonPath("$.libraries[0].metadataBackfill.complete").value(false))
        .andExpect(jsonPath("$.libraries[0].metadataBackfill.fields[0].fieldKey").value("title"))
        .andExpect(jsonPath("$.libraries[0].metadataBackfill.fields[0].filledDocuments").value(2));

    verify(searchStatusService).statusForOrganization(actingAdminOrganizationId);
  }

  @Test
  void diagnosisPassesQuestionContextAndTrackedDocumentThroughUnchanged() throws Exception {
    UUID profileId = UUID.randomUUID();
    UUID trackedId = UUID.randomUUID();
    when(searchDiagnosisService.diagnose(any(), any()))
        .thenReturn(
            new SearchDiagnosis(
                "Was gilt bei Gebührenbefreiung?",
                DiagnosisContextType.PERMISSION_PROFILE,
                "Bürgerbüro",
                Instant.parse("2026-09-01T10:00:00Z"),
                List.of(),
                List.of(),
                new RetrievalExplanation(List.of()),
                List.of(),
                Map.of(),
                null));

    mockMvc
        .perform(
            post("/api/v1/admin/search/diagnosis")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"question":"  Was gilt bei Gebührenbefreiung?  ",
                     "contextType":"PERMISSION_PROFILE",
                     "permissionProfileId":"%s",
                     "trackedDocumentId":"%s"}
                    """
                        .formatted(profileId, trackedId))
                .with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.contextLabel").value("Rechteprofil „Bürgerbüro“"));

    ArgumentCaptor<DiagnosisQuery> captor = ArgumentCaptor.forClass(DiagnosisQuery.class);
    verify(searchDiagnosisService).diagnose(any(), captor.capture());
    DiagnosisQuery query = captor.getValue();
    assertThat(query.question()).isEqualTo("Was gilt bei Gebührenbefreiung?");
    assertThat(query.contextType()).isEqualTo(DiagnosisContextType.PERMISSION_PROFILE);
    assertThat(query.permissionProfileId()).isEqualTo(profileId);
    assertThat(query.trackedDocumentId()).isEqualTo(trackedId);
  }

  @Test
  void aBlankTestQuestionIsRejectedWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/search/diagnosis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"   \",\"contextType\":\"SELF\"}")
                .with(asAdmin()))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(searchDiagnosisService);
  }

  @Test
  void permissionProfilesAreListedForTheCallersOwnOrganization() throws Exception {
    UUID profileId = UUID.randomUUID();
    when(searchDiagnosisService.permissionProfiles(any()))
        .thenReturn(
            List.of(new SearchDiagnosisService.PermissionProfile(profileId, "Bürgerbüro", 4)));

    mockMvc
        .perform(get("/api/v1/admin/search/permission-profiles").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(profileId.toString()))
        .andExpect(jsonPath("$[0].name").value("Bürgerbüro"))
        .andExpect(jsonPath("$[0].libraryCount").value(4));
  }

  @Test
  void chunkEndpointsAreNotReachableForARegularUser() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/search/chunks/abc").with(asRegularUser()))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/v1/admin/search/documents/" + UUID.randomUUID() + "/chunks")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());
    verifyNoInteractions(chunkInspectionService);
  }

  @Test
  void chunkIsReadInTheCallersOrganizationAndCarriesNoEmbedding() throws Exception {
    UUID documentId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    when(chunkInspectionService.findChunk(actingAdminOrganizationId, "chunk-1"))
        .thenReturn(
            Optional.of(
                new ChunkInspection(
                    "chunk-1",
                    documentId,
                    "satzung.pdf",
                    libraryId,
                    "Satzungen",
                    3,
                    "§ 4 Befreiung\nAuf Antrag ...",
                    Map.of("chunk_index", 3, "location", "Seite 2"))));

    mockMvc
        .perform(get("/api/v1/admin/search/chunks/chunk-1").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chunkId").value("chunk-1"))
        .andExpect(jsonPath("$.documentId").value(documentId.toString()))
        .andExpect(jsonPath("$.documentTitle").value("satzung.pdf"))
        .andExpect(jsonPath("$.libraryName").value("Satzungen"))
        .andExpect(jsonPath("$.chunkIndex").value(3))
        .andExpect(jsonPath("$.content").value("§ 4 Befreiung\nAuf Antrag ..."))
        .andExpect(jsonPath("$.metadata.location").value("Seite 2"))
        .andExpect(jsonPath("$.embedding").doesNotExist())
        .andExpect(jsonPath("$.metadata.embedding").doesNotExist());

    verify(chunkInspectionService).findChunk(actingAdminOrganizationId, "chunk-1");
  }

  @Test
  void anUnknownOrForeignChunkIs404() throws Exception {
    when(chunkInspectionService.findChunk(any(), any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/admin/search/chunks/fremd").with(asAdmin()))
        .andExpect(status().isNotFound());
  }

  @Test
  void documentChunksAreListedInOrderWithTheEntitysChunkCount() throws Exception {
    UUID documentId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    when(chunkInspectionService.listDocumentChunks(actingAdminOrganizationId, documentId))
        .thenReturn(
            new DocumentChunks(
                documentId,
                "satzung.pdf",
                libraryId,
                "Satzungen",
                3,
                List.of(
                    new ChunkInspection(
                        "c0",
                        documentId,
                        "satzung.pdf",
                        libraryId,
                        "Satzungen",
                        0,
                        "Erster",
                        Map.of()),
                    new ChunkInspection(
                        "c1",
                        documentId,
                        "satzung.pdf",
                        libraryId,
                        "Satzungen",
                        1,
                        "Zweiter",
                        Map.of()))));

    mockMvc
        .perform(get("/api/v1/admin/search/documents/" + documentId + "/chunks").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.documentTitle").value("satzung.pdf"))
        .andExpect(jsonPath("$.chunkCount").value(3))
        .andExpect(jsonPath("$.chunks.length()").value(2))
        .andExpect(jsonPath("$.chunks[0].chunkId").value("c0"))
        .andExpect(jsonPath("$.chunks[1].content").value("Zweiter"))
        .andExpect(jsonPath("$.chunks[0].embedding").doesNotExist());
  }

  @Test
  void anUnknownOrForeignDocumentIs404() throws Exception {
    when(chunkInspectionService.listDocumentChunks(any(), any()))
        .thenThrow(new NotFoundException("Das Dokument wurde nicht gefunden."));

    mockMvc
        .perform(
            get("/api/v1/admin/search/documents/" + UUID.randomUUID() + "/chunks").with(asAdmin()))
        .andExpect(status().isNotFound());
  }
}
