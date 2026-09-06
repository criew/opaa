package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.chat.ChatSource;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.query.MetadataFilterOptions;
import io.opaa.query.MetadataFilterOptionsService;
import io.opaa.query.QueryOutcome;
import io.opaa.query.QueryResult;
import io.opaa.query.QueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
  @MockitoBean private MetadataFilterOptionsService metadataFilterOptionsService;
  @MockitoBean private UserService userService;

  @BeforeEach
  void setUp() {
    User user = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    user.setSystemRole(SystemRole.USER);
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && TEST_SUBJECT.equals(token.getSubject()))))
        .thenReturn(user);
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
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(response);

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
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(response);

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
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What is OPAA?\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<Boolean> useKnowledgeCaptor = ArgumentCaptor.forClass(Boolean.class);
    verify(queryService)
        .query(anyString(), any(), any(), useKnowledgeCaptor.capture(), any(), any());
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
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(response);

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
        .query(
            anyString(),
            any(),
            any(),
            useKnowledgeCaptor.capture(),
            libraryIdsCaptor.capture(),
            any());
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
  void queryWithoutAuthenticationReturns401() throws Exception {
    // #884: CurrentUserArgumentResolver throws ResponseStatusException(UNAUTHORIZED) when
    // UserProvisioningFilter never ran (no Jwt principal, as here without asTestUser()) and so
    // never populated the request attribute it reads; GlobalExceptionHandler's
    // @ExceptionHandler(ResponseStatusException.class) maps that to the matching status/body.
    mockMvc
        .perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"What?\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Nicht angemeldet"))
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void queryWithTransientAiExceptionReturns503() throws Exception {
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any(), any()))
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
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any(), any()))
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

  /** #1070: the request's metadata filter reaches the service as the domain record. */
  @Test
  void queryPassesTheMetadataFilterThrough() throws Exception {
    UUID chatId = UUID.randomUUID();
    var response =
        new QueryResult("Answer", List.of(), new QueryOutcome("gpt-4o", 100, 500L), chatId);
    when(queryService.query(anyString(), any(), any(), anyBoolean(), any(), any()))
        .thenReturn(response);

    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\": \"Was gilt?\", \"metadataFilter\": {\"documentTypes\":"
                        + " [\"VERMERK\"], \"documentDateFrom\": \"2024-01-01\"}}"))
        .andExpect(status().isOk());

    ArgumentCaptor<MetadataFilter> filterCaptor = ArgumentCaptor.forClass(MetadataFilter.class);
    verify(queryService)
        .query(anyString(), any(), any(), anyBoolean(), any(), filterCaptor.capture());
    assertThat(filterCaptor.getValue().documentTypes()).containsExactly("VERMERK");
    assertThat(filterCaptor.getValue().documentDateFrom()).isEqualTo(LocalDate.of(2024, 1, 1));
    assertThat(filterCaptor.getValue().documentDateTo()).isNull();
  }

  /**
   * #1070, "nur Kernfelder filtern; freie Schlagworte nie": a filter naming any other field - a
   * keyword, the title - is rejected outright, not silently ignored into a filter that does
   * nothing.
   */
  @Test
  void aMetadataFilterOnAnUnknownFieldIsRejectedWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\": \"Was gilt?\", \"metadataFilter\": {\"keywords\":"
                        + " [\"Gebühren\"]}}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\": \"Was gilt?\", \"metadataFilter\": {\"title\":"
                        + " \"Satzung\"}}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(queryService);
  }

  /**
   * #1242: the Betreff is a display field of the Aufnahmestrecke - a filter naming it is refused,
   * not silently dropped, and an unknown format field likewise.
   */
  @Test
  void aFilterOnANonFilterableFormatFieldIsRejectedWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\": \"Was gilt?\", \"metadataFilter\": {\"formatFields\":"
                        + " [{\"fieldKey\": \"mail_subject\", \"values\":"
                        + " [\"Bebauungsplan\"]}]}}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\": \"Was gilt?\", \"metadataFilter\": {\"formatFields\":"
                        + " [{\"fieldKey\": \"mail_zeichen\", \"values\": [\"X\"]}]}}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(queryService);
  }

  @Test
  void anImpossibleFilterDateIsRejectedWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/query")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\": \"Was gilt?\", \"metadataFilter\": {\"documentDateFrom\":"
                        + " \"2024-02-30\"}}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(queryService);
  }

  /** #1070: the options endpoint resolves the scope with the query's own parameters. */
  @Test
  void metadataFilterOptionsAreServedForTheCallersScope() throws Exception {
    UUID chatId = UUID.randomUUID();
    UUID libraryId = UUID.randomUUID();
    when(metadataFilterOptionsService.optionsFor(
            any(), eq(chatId), eq(false), eq(List.of(libraryId))))
        .thenReturn(
            new MetadataFilterOptions(
                4,
                List.of(
                    new MetadataFilterOptions.FieldOption(
                        CoreMetadataField.DOCUMENT_TYPE, 4, 4, 0.9),
                    new MetadataFilterOptions.FieldOption(
                        CoreMetadataField.DOCUMENT_DATE, 2, 4, 0.75)),
                List.of(new MetadataFilterOptions.DocumentTypeOption("VERMERK", "Vermerk", 4)),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                List.of()));

    mockMvc
        .perform(
            get("/api/v1/search/metadata-filter-options")
                .with(asTestUser())
                .param("chatId", chatId.toString())
                .param("useKnowledge", "false")
                .param("libraryIds", libraryId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalDocuments").value(4))
        .andExpect(jsonPath("$.fields[0].fieldKey").value("document_type"))
        .andExpect(jsonPath("$.fields[0].offered").value(true))
        .andExpect(jsonPath("$.fields[1].fieldKey").value("document_date"))
        .andExpect(jsonPath("$.fields[1].fillShare").value(0.5))
        .andExpect(jsonPath("$.fields[1].offered").value(false))
        .andExpect(jsonPath("$.documentTypes[0].code").value("VERMERK"))
        .andExpect(jsonPath("$.documentDateMin").value("2024-01-01"))
        .andExpect(jsonPath("$.documentDateMax").value("2024-12-31"));
  }

  private static ChatSource sourceReference(
      String fileName, double relevanceScore, int matchCount, Instant indexedAt, boolean cited) {
    ChatSource sourceReference = new ChatSource(fileName, relevanceScore, matchCount, cited);
    sourceReference.setIndexedAt(indexedAt);
    return sourceReference;
  }
}
