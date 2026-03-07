package io.opaa.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.TestSecurityConfig;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.IndexingAlreadyRunningException;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.UrlIndexingRequest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IndexingController.class)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class IndexingControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DocumentIndexingService documentIndexingService;
  @MockitoBean private IndexingJobService indexingJobService;

  @Test
  void triggerIndexingReturnsAcceptedWithRunningStatus() throws Exception {
    var job = new IndexingJob(JobStatus.RUNNING);
    when(documentIndexingService.triggerIndexing()).thenReturn(job);

    mockMvc
        .perform(post("/api/v1/indexing/trigger"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"))
        .andExpect(jsonPath("$.documentCount").value(0))
        .andExpect(jsonPath("$.totalDocuments").value(0))
        .andExpect(jsonPath("$.documentsSkipped").value(0));
  }

  @Test
  void triggerIndexingReturnsConflictWhenAlreadyRunning() throws Exception {
    when(documentIndexingService.triggerIndexing())
        .thenThrow(new IndexingAlreadyRunningException("An indexing job is already running"));

    mockMvc
        .perform(post("/api/v1/indexing/trigger"))
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
        .andExpect(jsonPath("$.message").value("No indexing job found"));
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
        .andExpect(jsonPath("$.message").value(containsString("5 skipped")));
  }

  @Test
  void triggerUrlIndexingWithBodyRoutesToUrlIndexing() throws Exception {
    var job = new IndexingJob(JobStatus.RUNNING);
    when(documentIndexingService.triggerUrlIndexing(any(UrlIndexingRequest.class))).thenReturn(job);

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"url":"https://example.com/files/","proxy":"proxy:8080",\
                    "credentials":"user:pass","insecureSsl":true}
                    """))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"));

    ArgumentCaptor<UrlIndexingRequest> captor = ArgumentCaptor.forClass(UrlIndexingRequest.class);
    verify(documentIndexingService).triggerUrlIndexing(captor.capture());
    UrlIndexingRequest captured = captor.getValue();
    org.assertj.core.api.Assertions.assertThat(captured.url())
        .isEqualTo("https://example.com/files/");
    org.assertj.core.api.Assertions.assertThat(captured.proxy()).isEqualTo("proxy:8080");
    org.assertj.core.api.Assertions.assertThat(captured.credentials()).isEqualTo("user:pass");
    org.assertj.core.api.Assertions.assertThat(captured.insecureSsl()).isTrue();
  }

  @Test
  void triggerWithEmptyBodyFallsBackToFilesystemIndexing() throws Exception {
    var job = new IndexingJob(JobStatus.RUNNING);
    when(documentIndexingService.triggerIndexing()).thenReturn(job);

    mockMvc
        .perform(
            post("/api/v1/indexing/trigger").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("RUNNING"));

    verify(documentIndexingService).triggerIndexing();
  }
}
