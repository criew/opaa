package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.dto.QueryMetadata;
import io.opaa.api.dto.QueryResponse;
import io.opaa.api.dto.SourceReference;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.query.QueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(QueryController.class)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class QueryControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private QueryService queryService;

  @Test
  void queryReturnsAnswerWithSources() throws Exception {
    var response =
        new QueryResponse(
            "The answer",
            List.of(
                new SourceReference("doc.md", 0.9, 2, Instant.parse("2025-01-15T10:30:00Z"), true)),
            new QueryMetadata("gpt-4o", 500, 1200),
            "conv-123");
    when(queryService.query(anyString(), any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What is OPAA?\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("The answer"))
        .andExpect(jsonPath("$.sources[0].fileName").value("doc.md"))
        .andExpect(jsonPath("$.sources[0].relevanceScore").value(0.9))
        .andExpect(jsonPath("$.sources[0].matchCount").value(2))
        .andExpect(jsonPath("$.sources[0].indexedAt").exists())
        .andExpect(jsonPath("$.sources[0].cited").value(true))
        .andExpect(jsonPath("$.metadata.model").value("gpt-4o"))
        .andExpect(jsonPath("$.metadata.tokenCount").value(500))
        .andExpect(jsonPath("$.metadata.durationMs").value(1200))
        .andExpect(jsonPath("$.conversationId").value("conv-123"));
  }

  @Test
  void queryWithConversationIdPassesItThrough() throws Exception {
    var response =
        new QueryResponse(
            "Answer", List.of(), new QueryMetadata("gpt-4o", 100, 500), "existing-conv");
    when(queryService.query(anyString(), any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"Follow-up?\", \"conversationId\": \"existing-conv\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.conversationId").value("existing-conv"));
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
    when(queryService.query(anyString(), any()))
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
    when(queryService.query(anyString(), any()))
        .thenThrow(new NonTransientAiException("Invalid API key"));

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
