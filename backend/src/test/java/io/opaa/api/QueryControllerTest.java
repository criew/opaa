package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.chat.ChatSource;
import io.opaa.query.QueryOutcome;
import io.opaa.query.QueryResult;
import io.opaa.query.QueryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(QueryController.class)
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class QueryControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private QueryService queryService;
  @MockitoBean private UserService userService;

  @BeforeEach
  void setUp() {
    User user = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    user.setSystemRole(SystemRole.USER);
    when(userService.findBySubjectAndIssuer(TEST_SUBJECT, TEST_ISSUER))
        .thenReturn(Optional.of(user));
  }

  private RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  @Test
  void queryReturnsAnswerWithSources() throws Exception {
    UUID chatId = UUID.randomUUID();
    var response =
        new QueryResult(
            "The answer",
            List.of(sourceReference("doc.md", 0.9, 2, Instant.parse("2025-01-15T10:30:00Z"), true)),
            new QueryOutcome("gpt-4o", 500, 1200L),
            chatId);
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
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
        .andExpect(jsonPath("$.chatId").value(chatId.toString()));
  }

  @Test
  void queryWithChatIdPassesItThrough() throws Exception {
    UUID chatId = UUID.randomUUID();
    var response =
        new QueryResult("Answer", List.of(), new QueryOutcome("gpt-4o", 100, 500L), chatId);
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"Follow-up?\", \"chatId\": \"" + chatId + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chatId").value(chatId.toString()));
  }

  @Test
  void queryWithoutUseKnowledgeInBodyDefaultsToTrue() throws Exception {
    var response =
        new QueryResult(
            "Answer", List.of(), new QueryOutcome("gpt-4o", 100, 500L), UUID.randomUUID());
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What is OPAA?\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Boolean> useKnowledgeCaptor = ArgumentCaptor.forClass(Boolean.class);
    verify(queryService).query(anyString(), any(), any(), useKnowledgeCaptor.capture(), any());
    assertThat(useKnowledgeCaptor.getValue()).isTrue();
  }

  @Test
  @SuppressWarnings("unchecked")
  void queryWithUseKnowledgeFalseAndLibraryIdsPassesBothThrough() throws Exception {
    UUID libraryId1 = UUID.randomUUID();
    UUID libraryId2 = UUID.randomUUID();
    var response =
        new QueryResult(
            "Answer", List.of(), new QueryOutcome("gpt-4o", 100, 500L), UUID.randomUUID());
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\": \"What is OPAA?\", \"useKnowledge\": false, \"libraryIds\":"
                        + " [\""
                        + libraryId1
                        + "\", \""
                        + libraryId2
                        + "\"]}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Boolean> useKnowledgeCaptor = ArgumentCaptor.forClass(Boolean.class);
    ArgumentCaptor<List<UUID>> libraryIdsCaptor = ArgumentCaptor.forClass(List.class);
    verify(queryService)
        .query(anyString(), any(), any(), useKnowledgeCaptor.capture(), libraryIdsCaptor.capture());
    assertThat(useKnowledgeCaptor.getValue()).isFalse();
    assertThat(libraryIdsCaptor.getValue()).containsExactly(libraryId1, libraryId2);
  }

  @Test
  void queryWithBlankQuestionReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void queryWithMissingBodyReturns400() throws Exception {
    mockMvc
        .perform(post("/api/v1/query").with(asTestUser()).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  void queryWithUnknownJwtSubjectReturns401() throws Exception {
    // #202/#875: QueryController#currentUser (the same pattern LibraryController, GroupController
    // and SpaceController use) throws ResponseStatusException(UNAUTHORIZED) directly when the
    // JWT subject resolves to no known user; GlobalExceptionHandler's
    // @ExceptionHandler(ResponseStatusException.class) maps that to the matching status/body.
    when(userService.findBySubjectAndIssuer(TEST_SUBJECT, TEST_ISSUER))
        .thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What?\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Benutzer nicht gefunden"))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void queryWithTransientAiExceptionReturns503() throws Exception {
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any()))
        .thenThrow(new TransientAiException("Service unavailable"));

    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What?\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("KI-Dienst vorübergehend nicht verfügbar"))
        .andExpect(jsonPath("$.status").value(503));
  }

  @Test
  void queryWithNonTransientAiExceptionReturns502() throws Exception {
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any()))
        .thenThrow(new NonTransientAiException("Invalid API key"));

    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What?\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value("Fehler im KI-Dienst"))
        .andExpect(jsonPath("$.status").value(502));
  }

  private static ChatSource sourceReference(
      String fileName, double relevanceScore, int matchCount, Instant indexedAt, boolean cited) {
    ChatSource sourceReference = new ChatSource(fileName, relevanceScore, matchCount, cited);
    sourceReference.setIndexedAt(indexedAt);
    return sourceReference;
  }
}
