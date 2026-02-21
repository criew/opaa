package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MockIndexingController.class)
@ActiveProfiles("mock")
class MockIndexingControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void triggerIndexingReturnsCompletedStatus() throws Exception {
    mockMvc
        .perform(post("/api/v1/indexing/trigger"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.documentCount").value(42))
        .andExpect(jsonPath("$.chunkCount").doesNotExist())
        .andExpect(jsonPath("$.message").value("Indexing completed successfully"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void getIndexingStatusReturnsCompletedStatus() throws Exception {
    mockMvc
        .perform(get("/api/v1/indexing/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.documentCount").value(42));
  }
}
