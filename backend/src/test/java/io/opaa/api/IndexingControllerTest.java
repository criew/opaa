package io.opaa.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
import io.opaa.indexing.IndexingAlreadyRunningException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.UrlIndexingRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(IndexingController.class)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class IndexingControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DocumentIndexingService documentIndexingService;
  @MockitoBean private IndexingJobService indexingJobService;
  @MockitoBean private UserService userService;

  private User currentUser;

  @BeforeEach
  void setUp() {
    currentUser = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    currentUser.setSystemRole(SystemRole.SYSTEM_ADMIN);
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
    when(documentIndexingService.triggerIndexing(eq(libraryId), eq(currentUser.getId()), eq(true)))
        .thenReturn(job);

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.documentCount").value(0))
        .andExpect(jsonPath("$.totalDocuments").value(0))
        .andExpect(jsonPath("$.documentsSkipped").value(0));
  }

  @Test
  void triggerIndexingReturnsConflictWhenAlreadyRunning() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(documentIndexingService.triggerIndexing(eq(libraryId), eq(currentUser.getId()), eq(true)))
        .thenThrow(new IndexingAlreadyRunningException("An indexing job is already running"));

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.documentsSkipped").value(0))
        .andExpect(jsonPath("$.message").value("An indexing job is already running"));
  }

  @Test
  void getStatusReturnsIdleWhenNoJobs() throws Exception {
    when(indexingJobService.getLatestJob()).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/indexing/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IDLE"))
        .andExpect(jsonPath("$.totalDocuments").value(0))
        .andExpect(jsonPath("$.documentsSkipped").value(0))
        .andExpect(jsonPath("$.message").value("Kein Indizierungslauf gefunden"));
  }

  @Test
  void getStatusReturnsLatestJob() throws Exception {
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.getLatestJob()).thenReturn(Optional.of(job));

    mockMvc
        .perform(get("/api/v1/indexing/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.documentsSkipped").value(0));
  }

  @Test
  void getStatusReturnsCompletedJobWithSkippedCount() throws Exception {
    var job = new IndexingJob(JobStatus.RUNNING);
    job.setStatus(JobStatus.COMPLETED);
    job.setDocumentsProcessed(10);
    job.setDocumentsFailed(1);
    job.setDocumentsSkipped(5);
    job.setDocumentsTotal(16);
    job.setCompletedAt(Instant.now());
    when(indexingJobService.getLatestJob()).thenReturn(Optional.of(job));

    mockMvc
        .perform(get("/api/v1/indexing/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.documentCount").value(10))
        .andExpect(jsonPath("$.documentsSkipped").value(5))
        .andExpect(jsonPath("$.message").value(containsString("5 übersprungen")));
  }

  @Test
  void triggerUrlIndexingWithBodyRoutesToUrlIndexing() throws Exception {
    UUID libraryId = UUID.randomUUID();
    var job = new IndexingJob(JobStatus.RUNNING);
    when(documentIndexingService.triggerUrlIndexing(
            any(UrlIndexingRequest.class), eq(libraryId), eq(currentUser.getId()), eq(true)))
        .thenReturn(job);

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"libraryId":"%s","url":"https://example.com/files/","proxy":"proxy:8080",\
                    "credentials":"user:pass","insecureSsl":true}
                    """
                        .formatted(libraryId)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"));

    ArgumentCaptor<UrlIndexingRequest> captor = ArgumentCaptor.forClass(UrlIndexingRequest.class);
    verify(documentIndexingService)
        .triggerUrlIndexing(captor.capture(), eq(libraryId), eq(currentUser.getId()), eq(true));
    UrlIndexingRequest captured = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(captured.url())
        .isEqualTo("https://example.com/files/");
    org.assertj.core.api.Assertions.assertThat(captured.proxy()).isEqualTo("proxy:8080");
    org.assertj.core.api.Assertions.assertThat(captured.credentials()).isEqualTo("user:pass");
    org.assertj.core.api.Assertions.assertThat(captured.insecureSsl()).isTrue();
  }

  @Test
  void triggerWithoutLibraryIdReturnsBadRequestAndDoesNotStartAJob() throws Exception {
    // #419 acceptance criteria: no libraryId -> 400, no run started. DocumentIndexingService
    // owns the actual validation (see DocumentIndexingServiceTest); this pins that its
    // ResponseStatusException reaches the client as the matching HTTP status.
    when(documentIndexingService.triggerIndexing(eq(null), eq(currentUser.getId()), eq(true)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "libraryId ist erforderlich"));

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("libraryId ist erforderlich"));
  }

  @Test
  void triggerWithInsufficientRoleReturnsForbidden() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(documentIndexingService.triggerIndexing(eq(libraryId), eq(currentUser.getId()), eq(true)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek"));

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Kein Zugriff auf diese Bibliothek"));
  }

  @Test
  void triggerWithAForeignLibraryReturnsNotFound() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(documentIndexingService.triggerIndexing(eq(libraryId), eq(currentUser.getId()), eq(true)))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Bibliothek nicht gefunden"));

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"libraryId\":\"" + libraryId + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Bibliothek nicht gefunden"));
  }
}
