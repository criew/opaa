package io.opaa.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import io.opaa.chat.Chat;
import io.opaa.chat.ChatImport;
import io.opaa.chat.ChatImportService;
import io.opaa.chat.ChatListEntry;
import io.opaa.chat.ChatService;
import io.opaa.chat.ImportedChatEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-level coverage of the seed-only transcript import: the route exists only with {@code
 * opaa.demo.chat-import.enabled}, binds the transcript into the domain record and answers with the
 * imported chat. Persistence and authorization: {@code ChatImportServiceIntegrationTest}.
 */
class ChatImportControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  private static final String TRANSCRIPT =
      """
      {"importKey": "gebuehr-personalausweis", "title": "Gebühr Personalausweis", "turns": [
        {"question": "Was kostet ein Personalausweis?", "answer": "42,60 Euro.",
         "askedAt": "2026-09-01T08:15:00Z", "answeredAt": "2026-09-01T08:15:20Z",
         "sources": [{"documentId": "%s", "cited": true}]},
        {"question": "Und unter 24?", "answer": "26,20 Euro.",
         "askedAt": "2026-09-01T08:17:00Z", "answeredAt": "2026-09-01T08:17:15Z"}
      ]}
      """;

  private static RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  private static User stubCurrentUser(UserService userService) {
    User currentUser = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    currentUser.setSystemRole(SystemRole.USER);
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && TEST_SUBJECT.equals(token.getSubject()))))
        .thenReturn(currentUser);
    return currentUser;
  }

  @Nested
  @WebMvcTest(ChatImportController.class)
  @ActiveProfiles({"test", "dev"})
  @Import(TestSecurityConfig.class)
  @TestPropertySource(properties = "opaa.demo.chat-import.enabled=true")
  class Enabled {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ChatImportService chatImportService;
    @MockitoBean private ChatService chatService;
    @MockitoBean private UserService userService;

    private User currentUser;

    @BeforeEach
    void setUp() {
      currentUser = stubCurrentUser(userService);
    }

    @Test
    void theTranscriptIsImportedForTheCallerAndAnsweredWith201() throws Exception {
      UUID spaceId = UUID.randomUUID();
      UUID documentId = UUID.randomUUID();
      Chat chat =
          new Chat(
              spaceId,
              currentUser.getId(),
              UUID.randomUUID(),
              "Gebühr Personalausweis",
              true,
              Set.of());
      when(chatImportService.importChat(eq(spaceId), any(), any())).thenReturn(chat.getId());
      when(chatService.findOwnedChat(eq(chat.getId()), any())).thenReturn(Optional.of(chat));

      mockMvc
          .perform(
              post("/api/v1/spaces/{spaceId}/chat-imports", spaceId)
                  .with(asTestUser())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(TRANSCRIPT.formatted(documentId)))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.id").value(chat.getId().toString()))
          .andExpect(jsonPath("$.title").value("Gebühr Personalausweis"));

      ArgumentCaptor<ChatImport> captor = ArgumentCaptor.forClass(ChatImport.class);
      verify(chatImportService).importChat(eq(spaceId), eq(currentUser.getId()), captor.capture());
      ChatImport transcript = captor.getValue();
      assertThat(transcript.importKey()).isEqualTo("gebuehr-personalausweis");
      assertThat(transcript.title()).isEqualTo("Gebühr Personalausweis");
      assertThat(transcript.turns()).hasSize(2);
      ChatImport.Turn first = transcript.turns().getFirst();
      assertThat(first.askedAt()).isEqualTo(Instant.parse("2026-09-01T08:15:00Z"));
      assertThat(first.answeredAt()).isEqualTo(Instant.parse("2026-09-01T08:15:20Z"));
      assertThat(first.sources()).containsExactly(new ChatImport.Source(documentId, true));
      assertThat(transcript.turns().get(1).sources()).isEmpty();
    }

    @Test
    void aTranscriptWithoutTurnsIsRefusedBeforeTheService() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/spaces/{spaceId}/chat-imports", UUID.randomUUID())
                  .with(asTestUser())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"title\": \"Leer\", \"turns\": []}"))
          .andExpect(status().isBadRequest());

      verify(chatImportService, never()).importChat(any(), any(), any());
    }

    @Test
    void aTranscriptWithoutImportKeyIsRefusedBeforeTheService() throws Exception {
      String withoutKey =
          TRANSCRIPT
              .formatted(UUID.randomUUID())
              .replace("\"importKey\": \"gebuehr-personalausweis\", ", "");

      mockMvc
          .perform(
              post("/api/v1/spaces/{spaceId}/chat-imports", UUID.randomUUID())
                  .with(asTestUser())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(withoutKey))
          .andExpect(status().isBadRequest());

      verify(chatImportService, never()).importChat(any(), any(), any());
    }

    @Test
    void theCallersImportedChatsAreListedWithKeyAndMarks() throws Exception {
      UUID spaceId = UUID.randomUUID();
      Chat chat =
          new Chat(spaceId, currentUser.getId(), UUID.randomUUID(), "Umbenannt", true, Set.of());
      Instant archivedAt = Instant.parse("2026-09-30T12:00:00Z");
      when(chatImportService.listImportedChats(spaceId, currentUser.getId()))
          .thenReturn(
              List.of(
                  new ImportedChatEntry("gebuehren", new ChatListEntry(chat, null, archivedAt))));

      mockMvc
          .perform(get("/api/v1/spaces/{spaceId}/chat-imports", spaceId).with(asTestUser()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].importKey").value("gebuehren"))
          .andExpect(jsonPath("$[0].chat.id").value(chat.getId().toString()))
          .andExpect(jsonPath("$[0].chat.title").value("Umbenannt"))
          .andExpect(jsonPath("$[0].chat.archivedAt").value("2026-09-30T12:00:00Z"));
    }

    @Test
    void aWhitespaceQuestionOrAnOverlongQuestionIsRefusedBeforeTheService() throws Exception {
      String whitespace =
          TRANSCRIPT.formatted(UUID.randomUUID()).replace("Was kostet ein Personalausweis?", "   ");
      String overlong =
          TRANSCRIPT
              .formatted(UUID.randomUUID())
              .replace("Was kostet ein Personalausweis?", "x".repeat(2001));

      for (String body : new String[] {whitespace, overlong}) {
        mockMvc
            .perform(
                post("/api/v1/spaces/{spaceId}/chat-imports", UUID.randomUUID())
                    .with(asTestUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isBadRequest());
      }

      verify(chatImportService, never()).importChat(any(), any(), any());
    }
  }

  @Nested
  @WebMvcTest(ChatImportController.class)
  @ActiveProfiles({"test", "dev"})
  @Import(TestSecurityConfig.class)
  class DisabledByDefault {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ChatImportService chatImportService;
    @MockitoBean private ChatService chatService;
    @MockitoBean private UserService userService;

    @BeforeEach
    void setUp() {
      stubCurrentUser(userService);
    }

    @Test
    void withoutTheSwitchTheRouteDoesNotExist() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/spaces/{spaceId}/chat-imports", UUID.randomUUID())
                  .with(asTestUser())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(TRANSCRIPT.formatted(UUID.randomUUID())))
          .andExpect(status().isNotFound());
      mockMvc
          .perform(get("/api/v1/spaces/{spaceId}/chat-imports", UUID.randomUUID()).with(asTestUser()))
          .andExpect(status().isNotFound());

      verify(chatImportService, never()).importChat(any(), any(), any());
      verify(chatImportService, never()).listImportedChats(any(), any());
    }
  }
}
