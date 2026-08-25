package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatConversation;
import io.opaa.chat.ChatCreation;
import io.opaa.chat.ChatPatch;
import io.opaa.chat.ChatService;
import io.opaa.common.NotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-level coverage for {@link ChatController} (#525 review, finding/nit e): status codes and
 * path-variable binding, with {@link ChatService} mocked - {@code ChatServiceIntegrationTest}
 * covers the actual authorization and persistence behaviour against a real database.
 */
@WebMvcTest(ChatController.class)
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class ChatControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ChatService chatService;
  @MockitoBean private UserService userService;

  private User currentUser;

  @BeforeEach
  void setUp() {
    currentUser = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    currentUser.setSystemRole(SystemRole.USER);
    when(userService.findOrCreateUser(eq(TEST_SUBJECT), eq(TEST_ISSUER), any(), any()))
        .thenReturn(currentUser);
  }

  private RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  /** The returned {@link Chat} carries its own (randomly assigned) id - see {@link Chat}'s ctor. */
  private ChatConversation sampleDetail(UUID spaceId) {
    Chat chat = new Chat(spaceId, currentUser.getId(), UUID.randomUUID(), null, true, Set.of());
    return new ChatConversation(chat, List.of());
  }

  @Test
  void createChatReturns201AndPassesTheSpaceIdFromThePathThrough() throws Exception {
    UUID spaceId = UUID.randomUUID();
    ChatConversation sample = sampleDetail(spaceId);
    when(chatService.createChat(eq(spaceId), any(), any())).thenReturn(sample);

    mockMvc
        .perform(
            post("/api/v1/spaces/{spaceId}/chats", spaceId)
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(sample.getId().toString()))
        .andExpect(jsonPath("$.spaceId").value(spaceId.toString()));

    ArgumentCaptor<UUID> spaceIdCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(chatService).createChat(spaceIdCaptor.capture(), any(), any());
    assertThat(spaceIdCaptor.getValue()).isEqualTo(spaceId);
  }

  @Test
  void createChatWithoutABodyStillSucceeds() throws Exception {
    UUID spaceId = UUID.randomUUID();
    when(chatService.createChat(eq(spaceId), any(), any())).thenReturn(sampleDetail(spaceId));

    mockMvc
        .perform(post("/api/v1/spaces/{spaceId}/chats", spaceId).with(asTestUser()))
        .andExpect(status().isCreated());
  }

  @Test
  void createChatWithATitleLongerThan255CharactersReturns400() throws Exception {
    UUID spaceId = UUID.randomUUID();
    String tooLongTitle = "x".repeat(256);

    mockMvc
        .perform(
            post("/api/v1/spaces/{spaceId}/chats", spaceId)
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"" + tooLongTitle + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").exists());

    verify(chatService, never()).createChat(any(), any(), any());
  }

  @Test
  void createChatWithFullBodyPassesEveryFieldToChatCreation() throws Exception {
    UUID spaceId = UUID.randomUUID();
    UUID libraryId1 = UUID.randomUUID();
    UUID libraryId2 = UUID.randomUUID();
    when(chatService.createChat(eq(spaceId), any(), any())).thenReturn(sampleDetail(spaceId));

    mockMvc
        .perform(
            post("/api/v1/spaces/{spaceId}/chats", spaceId)
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\": \"Frage zur Frist\", \"useKnowledge\": false,"
                        + " \"referencedLibraryIds\": [\""
                        + libraryId1
                        + "\", \""
                        + libraryId2
                        + "\"]}"))
        .andExpect(status().isCreated());

    ArgumentCaptor<ChatCreation> creationCaptor = ArgumentCaptor.forClass(ChatCreation.class);
    verify(chatService).createChat(eq(spaceId), any(), creationCaptor.capture());
    ChatCreation creation = creationCaptor.getValue();
    assertThat(creation.getTitle()).isEqualTo("Frage zur Frist");
    assertThat(creation.getUseKnowledge()).isFalse();
    assertThat(creation.getReferencedLibraryIds()).containsExactly(libraryId1, libraryId2);
  }

  @Test
  void listSpaceChatsPassesTheSpaceIdFromThePathThroughAndMapsEveryChat() throws Exception {
    UUID spaceId = UUID.randomUUID();
    Chat chat =
        new Chat(spaceId, currentUser.getId(), UUID.randomUUID(), "Meine Frage", true, Set.of());
    when(chatService.listChats(eq(spaceId), any())).thenReturn(List.of(chat));

    mockMvc
        .perform(get("/api/v1/spaces/{spaceId}/chats", spaceId).with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(chat.getId().toString()))
        .andExpect(jsonPath("$[0].title").value("Meine Frage"));

    verify(chatService).listChats(eq(spaceId), any());
  }

  @Test
  void getChatReturnsTheChatForItsAuthor() throws Exception {
    UUID chatId = UUID.randomUUID();
    UUID spaceId = UUID.randomUUID();
    ChatConversation sample = sampleDetail(spaceId);
    when(chatService.getChat(eq(chatId), any())).thenReturn(sample);

    mockMvc
        .perform(get("/api/v1/chats/{chatId}", chatId).with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(sample.getId().toString()));
  }

  @Test
  void getChatReturns404ForAForeignChat() throws Exception {
    UUID chatId = UUID.randomUUID();
    when(chatService.getChat(eq(chatId), any()))
        .thenThrow(new NotFoundException("Chat nicht gefunden"));

    mockMvc
        .perform(get("/api/v1/chats/{chatId}", chatId).with(asTestUser()))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateChatReturns200AndPassesTheChatIdFromThePathThrough() throws Exception {
    UUID chatId = UUID.randomUUID();
    UUID spaceId = UUID.randomUUID();
    ChatConversation sample = sampleDetail(spaceId);
    when(chatService.updateChat(eq(chatId), any(), any())).thenReturn(sample);

    mockMvc
        .perform(
            patch("/api/v1/chats/{chatId}", chatId)
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"Neuer Titel\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(sample.getId().toString()));
  }

  @Test
  void updateChatWithATitleLongerThan255CharactersReturns400() throws Exception {
    UUID chatId = UUID.randomUUID();
    String tooLongTitle = "x".repeat(256);

    mockMvc
        .perform(
            patch("/api/v1/chats/{chatId}", chatId)
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"" + tooLongTitle + "\"}"))
        .andExpect(status().isBadRequest());

    verify(chatService, never()).updateChat(any(), any(), any());
  }

  @Test
  void updateChatWithFullBodyPassesEveryFieldToChatPatch() throws Exception {
    UUID chatId = UUID.randomUUID();
    UUID spaceId = UUID.randomUUID();
    UUID libraryId1 = UUID.randomUUID();
    UUID libraryId2 = UUID.randomUUID();
    when(chatService.updateChat(eq(chatId), any(), any())).thenReturn(sampleDetail(spaceId));

    mockMvc
        .perform(
            patch("/api/v1/chats/{chatId}", chatId)
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\": \"Neuer Titel\", \"useKnowledge\": false,"
                        + " \"referencedLibraryIds\": [\""
                        + libraryId1
                        + "\", \""
                        + libraryId2
                        + "\"]}"))
        .andExpect(status().isOk());

    ArgumentCaptor<ChatPatch> patchCaptor = ArgumentCaptor.forClass(ChatPatch.class);
    verify(chatService).updateChat(eq(chatId), any(), patchCaptor.capture());
    ChatPatch patch = patchCaptor.getValue();
    assertThat(patch.getTitle()).isEqualTo("Neuer Titel");
    assertThat(patch.getUseKnowledge()).isFalse();
    assertThat(patch.getReferencedLibraryIds()).containsExactly(libraryId1, libraryId2);
  }

  @Test
  void updateChatReturns404ForAForeignChat() throws Exception {
    UUID chatId = UUID.randomUUID();
    when(chatService.updateChat(eq(chatId), any(), any()))
        .thenThrow(new NotFoundException("Chat nicht gefunden"));

    mockMvc
        .perform(
            patch("/api/v1/chats/{chatId}", chatId)
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteChatReturns204AndPassesTheChatIdFromThePathThrough() throws Exception {
    UUID chatId = UUID.randomUUID();

    mockMvc
        .perform(delete("/api/v1/chats/{chatId}", chatId).with(asTestUser()))
        .andExpect(status().isNoContent());

    ArgumentCaptor<UUID> chatIdCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(chatService).deleteChat(chatIdCaptor.capture(), any());
    assertThat(chatIdCaptor.getValue()).isEqualTo(chatId);
  }

  @Test
  void deleteChatReturns404ForAForeignChat() throws Exception {
    UUID chatId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(new NotFoundException("Chat nicht gefunden"))
        .when(chatService)
        .deleteChat(eq(chatId), any());

    mockMvc
        .perform(delete("/api/v1/chats/{chatId}", chatId).with(asTestUser()))
        .andExpect(status().isNotFound());
  }
}
