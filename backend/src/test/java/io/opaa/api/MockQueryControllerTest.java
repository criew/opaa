package io.opaa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MockQueryController.class)
@ActiveProfiles("mock")
class MockQueryControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void queryReturnsAnswerWithSources() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What is the architecture?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").isNotEmpty())
        .andExpect(jsonPath("$.sources").isArray())
        .andExpect(
            jsonPath("$.sources.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.sources[0].fileName").isNotEmpty())
        .andExpect(jsonPath("$.sources[0].relevanceScore").isNumber())
        .andExpect(jsonPath("$.metadata.model").value("gpt-4o"));
  }

  @Test
  void queryWithBlankQuestionReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void queryWithNullQuestionReturns400() throws Exception {
    mockMvc
        .perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists())
        .andExpect(jsonPath("$.status").value(400));
  }

  @Test
  void queryWithMissingBodyReturns400() throws Exception {
    mockMvc
        .perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }
}
