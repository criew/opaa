package io.opaa.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(IndexingController.class)
@ActiveProfiles("test")
class IndexingControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DocumentIndexingService documentIndexingService;
  @MockitoBean private IndexingJobService indexingJobService;

  @Test
  void triggerIndexingReturnsJobStatus() throws Exception {
    var job = new IndexingJob(JobStatus.COMPLETED);
    job.setDocumentsProcessed(5);
    job.setDocumentsFailed(1);
    when(documentIndexingService.triggerIndexing()).thenReturn(job);

    mockMvc
        .perform(post("/api/v1/indexing/trigger"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.documentCount").value(5));
  }

  @Test
  void getStatusReturnsIdleWhenNoJobs() throws Exception {
    when(indexingJobService.getLatestJob()).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/indexing/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IDLE"))
        .andExpect(jsonPath("$.message").value("No indexing job found"));
  }

  @Test
  void getStatusReturnsLatestJob() throws Exception {
    var job = new IndexingJob(JobStatus.RUNNING);
    when(indexingJobService.getLatestJob()).thenReturn(Optional.of(job));

    mockMvc
        .perform(get("/api/v1/indexing/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"));
  }
}
