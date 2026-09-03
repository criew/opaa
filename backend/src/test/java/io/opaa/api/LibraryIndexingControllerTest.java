package io.opaa.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingRunDetail;
import io.opaa.indexing.IndexingRunEvent;
import io.opaa.indexing.IndexingStatusView;
import io.opaa.indexing.JobStatus;
import io.opaa.library.AssetGrantService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDocumentService;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.SourceConnectionTestService;
import io.opaa.space.SpaceAssetAssociationService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * #478: {@code POST}/{@code GET /api/v1/libraries/{libraryId}/indexing[/status]} replace the old
 * {@code /api/v1/indexing/trigger}/{@code /status} - both now read the run's target and
 * quellkonfiguration from the library itself, so the only thing left on the wire is the path
 * variable. {@link DocumentIndexingService} owns every authorization/validation decision (see
 * {@code DocumentIndexingServiceTest}); this pins that its {@code io.opaa.common} domain exceptions
 * reach the client as the matching HTTP status/body.
 */
@WebMvcTest(LibraryController.class)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class LibraryIndexingControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private KnowledgeLibraryService libraryService;
  @MockitoBean private AssetGrantService grantService;
  @MockitoBean private LibraryDocumentService documentService;
  @MockitoBean private LibraryFolderService folderService;
  @MockitoBean private DocumentIndexingService indexingService;
  @MockitoBean private UserService userService;
  @MockitoBean private SourceConnectionTestService sourceConnectionTestService;
  @MockitoBean private SpaceAssetAssociationService associationService;

  private User currentUser;
  private CurrentUser caller;

  @BeforeEach
  void setUp() {
    currentUser = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    currentUser.setSystemRole(SystemRole.USER);
    caller =
        CurrentUser.of(
            currentUser.getId(),
            currentUser.getOrganizationId(),
            currentUser.getSystemRole(),
            currentUser.getDisplayName());
    when(userService.findOrCreateUser(eq(TEST_SUBJECT), eq(TEST_ISSUER), any(), any()))
        .thenReturn(currentUser);
  }

  private RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  @Test
  void triggerIndexingReturnsAcceptedWithRunningStatus() throws Exception {
    UUID libraryId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingService.triggerIndexing(eq(libraryId), eq(caller), isNull())).thenReturn(job);

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.documentCount").value(0))
        .andExpect(jsonPath("$.totalDocuments").value(0))
        .andExpect(jsonPath("$.documentsSkipped").value(0));
  }

  @Test
  void triggerIndexingHandsARequestedRunModeThroughAndReportsItOnTheRun() throws Exception {
    // ADR-0023, Entscheidung 4 (#1136): the Betriebsart is a query parameter on the trigger and a
    // field of every run in the list.
    UUID libraryId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setRunMode(IndexingRunMode.FULL);
    when(indexingService.triggerIndexing(eq(libraryId), eq(caller), eq(IndexingRunMode.FULL)))
        .thenReturn(job);
    when(indexingService.getRecentRuns(eq(libraryId), eq(caller)))
        .thenReturn(List.of(new IndexingRunDetail(job, List.of())));

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/indexing?runMode=FULL").with(asTestUser()))
        .andExpect(status().isAccepted());
    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/runs").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runs[0].runMode").value("FULL"));
  }

  @Test
  void triggerIndexingRejectsARunModeTheSourceTypeDoesNotSupportWith400() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.triggerIndexing(
            eq(libraryId), eq(caller), eq(IndexingRunMode.INCREMENTAL)))
        .thenThrow(
            new ValidationException(
                "Betriebsart INCREMENTAL ist für Bibliotheken vom Typ FILESYSTEM nicht verfügbar;"
                    + " möglich: FULL"));

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/indexing?runMode=INCREMENTAL")
                .with(asTestUser()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("möglich: FULL")));
    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/indexing?runMode=PARTIAL").with(asTestUser()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void triggerIndexingReturnsConflictWhenALibraryHasNoRunType() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.triggerIndexing(eq(libraryId), eq(caller), isNull()))
        .thenThrow(
            new ConflictException("Für UPLOAD-Bibliotheken gibt es keinen Indizierungslauf"));

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.error").value("Für UPLOAD-Bibliotheken gibt es keinen Indizierungslauf"));
  }

  @Test
  void triggerIndexingReturnsConflictWhenAlreadyRunningForThisLibrary() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.triggerIndexing(eq(libraryId), eq(caller), isNull()))
        .thenThrow(
            new ConflictException("Für diese Bibliothek läuft bereits ein Indizierungslauf"));

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(containsString("läuft bereits ein Indizierungslauf")));
  }

  @Test
  void triggerWithInsufficientRoleReturnsForbidden() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.triggerIndexing(eq(libraryId), eq(caller), isNull()))
        .thenThrow(new AccessDeniedException("Kein Zugriff auf diese Bibliothek"));

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Kein Zugriff auf diese Bibliothek"));
  }

  @Test
  void triggerWithAForeignLibraryReturnsNotFound() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.triggerIndexing(eq(libraryId), eq(caller), isNull()))
        .thenThrow(new NotFoundException("Bibliothek nicht gefunden"));

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Bibliothek nicht gefunden"));
  }

  @Test
  void getStatusReturnsIdleWhenTheLibraryNeverRan() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.getStatus(eq(libraryId), eq(caller)))
        .thenReturn(new IndexingStatusView(Optional.empty(), false));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/status").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IDLE"))
        .andExpect(jsonPath("$.totalDocuments").value(0))
        .andExpect(jsonPath("$.documentsSkipped").value(0))
        .andExpect(jsonPath("$.message").value("Kein Indizierungslauf gefunden"))
        .andExpect(jsonPath("$.libraryId").value(libraryId.toString()));
  }

  @Test
  void getStatusReturnsTheLibrarysLatestJob() throws Exception {
    UUID libraryId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(libraryId);
    when(indexingService.getStatus(eq(libraryId), eq(caller)))
        .thenReturn(new IndexingStatusView(Optional.of(job), false));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/status").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.libraryId").value(libraryId.toString()));
  }

  @Test
  void getStatusReturnsCompletedJobWithSkippedCount() throws Exception {
    UUID libraryId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setStatus(JobStatus.COMPLETED);
    job.setDocumentsProcessed(10);
    job.setDocumentsFailed(1);
    job.setDocumentsSkipped(5);
    job.setDocumentsTotal(16);
    // #518: an RSS_FEED run's documentsIndexedTotal (here: entries plus their attachments) can
    // exceed documentsProcessed (feed entries alone) - distinct values below prove the response
    // actually carries the new field rather than aliasing documentCount.
    job.setDocumentsIndexedTotal(23);
    job.setCompletedAt(Instant.now());
    when(indexingService.getStatus(eq(libraryId), eq(caller)))
        .thenReturn(new IndexingStatusView(Optional.of(job), false));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/status").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.documentCount").value(10))
        .andExpect(jsonPath("$.documentsSkipped").value(5))
        .andExpect(jsonPath("$.documentsFailed").value(1))
        .andExpect(jsonPath("$.documentsIndexedTotal").value(23))
        .andExpect(jsonPath("$.message").value(containsString("5 übersprungen")))
        .andExpect(jsonPath("$.message").value(containsString("1 fehlgeschlagen")));
  }

  @Test
  void getStatusOfAFailedRunHidesTheRawErrorDetailBelowManagerButShowsItForAManager()
      throws Exception {
    // #507/#659: job.getErrorMessage() is the raw exception message from the executor that ran
    // this job - e.g. "/data/dokumente/geheim: No such file or directory" for a FILESYSTEM run
    // whose configured directory vanished, the exact internal server path #507 already hides from
    // a VIEWER on the source configuration display itself.
    UUID libraryId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setStatus(JobStatus.FAILED);
    job.setLibraryId(libraryId);
    job.setErrorMessage("/data/dokumente/geheim: No such file or directory");

    when(indexingService.getStatus(eq(libraryId), eq(caller)))
        .thenReturn(new IndexingStatusView(Optional.of(job), false));
    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/status").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(
            jsonPath("$.message")
                .value("Indizierung fehlgeschlagen. Details sind für Verwaltende sichtbar."))
        .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(containsString("/data"))));

    when(indexingService.getStatus(eq(libraryId), eq(caller)))
        .thenReturn(new IndexingStatusView(Optional.of(job), true));
    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/status").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "Indizierung fehlgeschlagen: /data/dokumente/geheim: No such file or"
                        + " directory"));
  }

  @Test
  void listIndexingRunsReturnsRunsWithTheirProtocol() throws Exception {
    UUID libraryId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setStatus(JobStatus.COMPLETED);
    job.setLibraryId(libraryId);
    job.setDocumentsProcessed(1);
    job.setDocumentsSkipped(1);
    job.setDocumentsTotal(2);
    job.setEventsTruncatedCount(3);
    var event =
        new IndexingRunEvent(
            job.getId(),
            IndexingEventCategory.UNSUPPORTED_FORMAT,
            "Dateiformat wird nicht unterstützt",
            "bad.csv");
    when(indexingService.getRecentRuns(eq(libraryId), eq(caller)))
        .thenReturn(List.of(new IndexingRunDetail(job, List.of(event))));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/runs").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runs[0].id").value(job.getId().toString()))
        .andExpect(jsonPath("$.runs[0].status").value("COMPLETED"))
        .andExpect(jsonPath("$.runs[0].eventsTruncatedCount").value(3))
        .andExpect(jsonPath("$.runs[0].events[0].category").value("UNSUPPORTED_FORMAT"))
        .andExpect(
            jsonPath("$.runs[0].events[0].message").value("Dateiformat wird nicht unterstützt"))
        .andExpect(jsonPath("$.runs[0].events[0].reference").value("bad.csv"));
  }

  @Test
  void listIndexingRunsWithInsufficientAccessReturnsForbidden() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.getRecentRuns(eq(libraryId), eq(caller)))
        .thenThrow(new AccessDeniedException("Kein Zugriff auf diese Bibliothek"));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/runs").with(asTestUser()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Kein Zugriff auf diese Bibliothek"));
  }

  @Test
  void getStatusWithInsufficientAccessReturnsForbidden() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.getStatus(eq(libraryId), eq(caller)))
        .thenThrow(new AccessDeniedException("Kein Zugriff auf diese Bibliothek"));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/status").with(asTestUser()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Kein Zugriff auf diese Bibliothek"));
  }
}
