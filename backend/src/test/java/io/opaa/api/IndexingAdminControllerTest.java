package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.AuditEventType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.DocumentPipeline;
import io.opaa.indexing.DocumentPipelineRegistry;
import io.opaa.indexing.DocumentPipelineResult;
import io.opaa.indexing.DocumentPipelineSource;
import io.opaa.indexing.LowChunkDocumentAuditService;
import io.opaa.indexing.PipelineReindexResult;
import io.opaa.indexing.PipelineReindexService;
import io.opaa.indexing.PipelineVersionProgress;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link IndexingAdminController} in isolation, {@link LowChunkDocumentAuditService} mocked -
 * proves the {@code SYSTEM_ADMIN} access bar and that the caller's own organizationId, not a
 * request parameter, drives the query (#1090).
 */
@WebMvcTest(IndexingAdminController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class IndexingAdminControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private LowChunkDocumentAuditService lowChunkDocumentAuditService;
  @MockitoBean private PipelineReindexService pipelineReindexService;
  @MockitoBean private DocumentPipelineRegistry pipelineRegistry;
  @MockitoBean private AuditEventRecorder auditEventRecorder;
  @MockitoBean private UserService userService;

  /** Stands in for the registered pipelines without needing Tika or a chunking configuration. */
  private record StubPipeline(String id, short version, java.util.Set<String> handledFormats)
      implements DocumentPipeline {

    @Override
    public DocumentPipelineResult run(DocumentPipelineSource source) {
      throw new UnsupportedOperationException("not exercised by controller tests");
    }
  }

  private static final DocumentPipeline TIKA_FALLBACK =
      new StubPipeline("tika-fallback", (short) 1, java.util.Set.of());

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
    org.mockito.Mockito.lenient()
        .when(pipelineRegistry.pipelines())
        .thenReturn(List.of(TIKA_FALLBACK));
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
  void listLowChunkDocumentsAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/indexing/low-chunk-documents").with(asRegularUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void listLowChunkDocumentsScopesToTheCallersOwnOrganization() throws Exception {
    Pageable pageable =
        PageRequest.of(0, 20, Sort.by(Sort.Order.asc("libraryId"), Sort.Order.asc("fileName")));
    var entry =
        new LowChunkDocumentAuditService.LowChunkDocumentEntry(
            UUID.randomUUID(), UUID.randomUUID(), "Satzungen", "scan.pdf", 12_345L, 0);
    when(lowChunkDocumentAuditService.findLowChunkDocuments(actingAdminOrganizationId, 0, pageable))
        .thenReturn(new PageImpl<>(List.of(entry), pageable, 1));

    mockMvc
        .perform(get("/api/v1/admin/indexing/low-chunk-documents").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].fileName").value("scan.pdf"))
        .andExpect(jsonPath("$.items[0].libraryName").value("Satzungen"))
        .andExpect(jsonPath("$.items[0].chunkCount").value(0))
        .andExpect(jsonPath("$.totalElements").value(1));

    verify(lowChunkDocumentAuditService)
        .findLowChunkDocuments(actingAdminOrganizationId, 0, pageable);
  }

  @Test
  void listLowChunkDocumentsPassesThroughChunkCountThresholdAndPaging() throws Exception {
    Pageable pageable =
        PageRequest.of(1, 5, Sort.by(Sort.Order.asc("libraryId"), Sort.Order.asc("fileName")));
    when(lowChunkDocumentAuditService.findLowChunkDocuments(actingAdminOrganizationId, 3, pageable))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    mockMvc
        .perform(
            get("/api/v1/admin/indexing/low-chunk-documents")
                .param("chunkCountThreshold", "3")
                .param("page", "1")
                .param("size", "5")
                .with(asAdmin()))
        .andExpect(status().isOk());

    verify(lowChunkDocumentAuditService)
        .findLowChunkDocuments(actingAdminOrganizationId, 3, pageable);
  }

  @Test
  void listLowChunkDocumentsRejectsAnOversizedPageWith400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/indexing/low-chunk-documents").param("size", "101").with(asAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("size muss zwischen 1 und 100 liegen, war 101"));
  }

  @Test
  void listLowChunkDocumentsRejectsANegativePageWith400() throws Exception {
    // #1090 review: PageRequest.of's own English "Page index must not be less than zero" must
    // never reach the response body verbatim (AGENTS.md, Projektsprache) - validated before it.
    mockMvc
        .perform(
            get("/api/v1/admin/indexing/low-chunk-documents").param("page", "-1").with(asAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("page darf nicht negativ sein, war -1"));
  }

  @Test
  void pipelineVersionsAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/indexing/pipeline-versions").with(asRegularUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void pipelineVersionsReportsRegisteredPipelinesAndPerLibraryFillState() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(pipelineReindexService.progressForOrganization(actingAdminOrganizationId))
        .thenReturn(List.of(new PipelineVersionProgress(libraryId, 10L, 7L, 3L)));

    mockMvc
        .perform(get("/api/v1/admin/indexing/pipeline-versions").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pipelines[0].id").value("tika-fallback"))
        .andExpect(jsonPath("$.pipelines[0].currentVersion").value(1))
        .andExpect(jsonPath("$.libraries[0].libraryId").value(libraryId.toString()))
        .andExpect(jsonPath("$.libraries[0].totalChunks").value(10))
        .andExpect(jsonPath("$.libraries[0].currentVersionChunks").value(7))
        .andExpect(jsonPath("$.libraries[0].staleChunks").value(3))
        .andExpect(jsonPath("$.libraries[0].complete").value(false));

    verify(pipelineReindexService).progressForOrganization(actingAdminOrganizationId);
  }

  @Test
  void pipelineReindexScopesToTheCallersOwnOrganizationAndReportsWhenDone() throws Exception {
    when(pipelineReindexService.reindexBatch(actingAdminOrganizationId, "tika-fallback", 1, 5))
        .thenReturn(new PipelineReindexResult(0, 0, 0, 0));

    mockMvc
        .perform(
            post("/api/v1/admin/indexing/pipeline-reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pipelineId\":\"tika-fallback\",\"belowVersion\":1,\"batchSize\":5}")
                .with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.done").value(true))
        .andExpect(jsonPath("$.reindexedDocuments").value(0));

    verify(pipelineReindexService).reindexBatch(actingAdminOrganizationId, "tika-fallback", 1, 5);
  }

  @Test
  void pipelineReindexRejectsAnUnknownPipelineWith400() throws Exception {
    // Without this guard an unbekannte id would report "done" for a re-index that never had a
    // chance of matching anything - a silent no-op dressed as success.
    mockMvc
        .perform(
            post("/api/v1/admin/indexing/pipeline-reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pipelineId\":\"docling-pdf\",\"belowVersion\":2}")
                .with(asAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Unbekannte Pipeline: docling-pdf"));
  }

  @Test
  void pipelineReindexRejectsABelowVersionAboveThePipelinesOwnVersionWith400() throws Exception {
    // A bound above the pipeline's own version can never be reached: every rewritten chunk would
    // still be below it, so the same documents would be selected on every following batch -
    // unbounded embedding work, not a slow run.
    mockMvc
        .perform(
            post("/api/v1/admin/indexing/pipeline-reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pipelineId\":\"tika-fallback\",\"belowVersion\":2}")
                .with(asAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error")
                .value(
                    "belowVersion darf höchstens der aktuellen Version der Pipeline tika-fallback"
                        + " entsprechen (1), war 2"));

    verify(pipelineReindexService, org.mockito.Mockito.never())
        .reindexBatch(any(), any(), org.mockito.ArgumentMatchers.anyInt(), anyInt());
  }

  @Test
  void pipelineReindexRejectsABelowVersionBelowOneWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/indexing/pipeline-reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pipelineId\":\"tika-fallback\",\"belowVersion\":0}")
                .with(asAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("belowVersion muss mindestens 1 sein, war 0"));
  }

  @Test
  void pipelineReindexRecordsAnAuditEventForTheTriggeringCall() throws Exception {
    when(pipelineReindexService.reindexBatch(actingAdminOrganizationId, "tika-fallback", 1, 10))
        .thenReturn(new PipelineReindexResult(4, 1, 2, 0));

    mockMvc
        .perform(
            post("/api/v1/admin/indexing/pipeline-reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pipelineId\":\"tika-fallback\",\"belowVersion\":1}")
                .with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skippedDocuments").value(2))
        .andExpect(jsonPath("$.done").value(false));

    ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(auditCaptor.capture());
    AuditEvent event = auditCaptor.getValue();
    assertThat(event.eventType()).isEqualTo(AuditEventType.INDEXING_PIPELINE_REINDEX_TRIGGERED);
    assertThat(event.organizationId()).isEqualTo(actingAdminOrganizationId);
    assertThat(event.actorUserId()).isEqualTo(actingAdminId);
    assertThat(event.after())
        .containsEntry("belowVersion", 1)
        .containsEntry("reindexedDocuments", 4)
        .containsEntry("markedForNextRun", 1)
        .containsEntry("skippedDocuments", 2);
  }

  @Test
  void pipelineReindexRejectsAnOversizedBatchWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/indexing/pipeline-reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pipelineId\":\"tika-fallback\",\"belowVersion\":1,\"batchSize\":101}")
                .with(asAdmin()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("batchSize muss zwischen 1 und 100 liegen, war 101"));
  }

  @Test
  void pipelineReindexAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/indexing/pipeline-reindex")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pipelineId\":\"tika-fallback\",\"belowVersion\":1}")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());
  }
}
