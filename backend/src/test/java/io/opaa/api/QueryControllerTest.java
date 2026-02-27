package io.opaa.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import io.opaa.query.QueryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QueryController.class)
@ActiveProfiles("test")
class QueryControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private QueryService queryService;

  @Test
  void queryReturnsAnswerWithSources() throws Exception {
    var response =
        new QueryResponse(
            "The answer",
            List.of(new SourceReference("doc.md", 0.9, "excerpt text", true)),
            new QueryMetadata("gpt-4o", 500, 1200));
    when(queryService.query(anyString())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What is OPAA?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("The answer"))
        .andExpect(jsonPath("$.sources[0].fileName").value("doc.md"))
        .andExpect(jsonPath("$.sources[0].relevanceScore").value(0.9))
        .andExpect(jsonPath("$.sources[0].excerpt").value("excerpt text"))
        .andExpect(jsonPath("$.metadata.model").value("gpt-4o"))
        .andExpect(jsonPath("$.metadata.tokenCount").value(500))
        .andExpect(jsonPath("$.metadata.durationMs").value(1200));
  }

  @Test
  void queryWithBlankQuestionReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void queryWithMissingBodyReturns400() throws Exception {
    mockMvc
        .perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  void queryWithTransientAiExceptionReturns503() throws Exception {
    when(queryService.query(anyString()))
        .thenThrow(new TransientAiException("Service unavailable"));

    mockMvc
        .perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What?\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("AI service temporarily unavailable"))
        .andExpect(jsonPath("$.status").value(503));
  }

  @Test
  void queryWithNonTransientAiExceptionReturns502() throws Exception {
    when(queryService.query(anyString())).thenThrow(new NonTransientAiException("Invalid API key"));

    mockMvc
        .perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What?\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("AI service error"))
        .andExpect(jsonPath("$.status").value(502));
  }
}
