package io.opaa.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingRunDetail;
import io.opaa.indexing.IndexingRunEvent;
import io.opaa.indexing.JobStatus;
import io.opaa.library.AssetGrantService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDocumentService;
import io.opaa.library.SourceConnectionTestService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

/**
 * #478: {@code POST}/{@code GET /api/v1/libraries/{libraryId}/indexing[/status]} replace the old
 * {@code /api/v1/indexing/trigger}/{@code /status} - both now read the run's target and
 * quellkonfiguration from the library itself, so the only thing left on the wire is the path
 * variable. {@link DocumentIndexingService} owns every authorization/validation decision (see
 * {@code DocumentIndexingServiceTest}); this pins that its {@link ResponseStatusException}s reach
 * the client as the matching HTTP status/body.
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
  @MockitoBean private DocumentIndexingService indexingService;
  @MockitoBean private UserService userService;
  @MockitoBean private SourceConnectionTestService sourceConnectionTestService;

  private User currentUser;

  @BeforeEach
  void setUp() {
    currentUser = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    currentUser.setSystemRole(SystemRole.USER);
    when(userService.findBySubjectAndIssuer(TEST_SUBJECT, TEST_ISSUER))
        .thenReturn(Optional.of(currentUser));
  }

  private RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  @Test
  void triggerIndexingReturnsAcceptedWithRunningStatus() throws Exception {
    UUID libraryId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingService.triggerIndexing(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenReturn(job);

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.documentCount").value(0))
        .andExpect(jsonPath("$.totalDocuments").value(0))
        .andExpect(jsonPath("$.documentsSkipped").value(0));
  }

  @Test
  void triggerIndexingReturnsConflictWhenALibraryHasNoRunType() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.triggerIndexing(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.CONFLICT, "Für UPLOAD-Bibliotheken gibt es keinen Indizierungslauf"));

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.error").value("Für UPLOAD-Bibliotheken gibt es keinen Indizierungslauf"));
  }

  @Test
  void triggerIndexingReturnsConflictWhenAlreadyRunningForThisLibrary() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.triggerIndexing(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.CONFLICT, "Für diese Bibliothek läuft bereits ein Indizierungslauf"));

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(containsString("läuft bereits ein Indizierungslauf")));
  }

  @Test
  void triggerWithInsufficientRoleReturnsForbidden() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.triggerIndexing(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek"));

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Kein Zugriff auf diese Bibliothek"));
  }

  @Test
  void triggerWithAForeignLibraryReturnsNotFound() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.triggerIndexing(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));

    mockMvc
        .perform(post("/api/v1/libraries/" + libraryId + "/indexing").with(asTestUser()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Bibliothek nicht gefunden"));
  }

  @Test
  void getStatusReturnsIdleWhenTheLibraryNeverRan() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.getStatus(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenReturn(Optional.empty());

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
    when(indexingService.getStatus(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenReturn(Optional.of(job));

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
    when(indexingService.getStatus(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenReturn(Optional.of(job));

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
    when(indexingService.getRecentRuns(eq(libraryId), eq(currentUser.getId()), eq(false)))
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
    when(indexingService.getRecentRuns(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek"));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/runs").with(asTestUser()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Kein Zugriff auf diese Bibliothek"));
  }

  @Test
  void getStatusWithInsufficientAccessReturnsForbidden() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(indexingService.getStatus(eq(libraryId), eq(currentUser.getId()), eq(false)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek"));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/indexing/status").with(asTestUser()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Kein Zugriff auf diese Bibliothek"));
  }
}
