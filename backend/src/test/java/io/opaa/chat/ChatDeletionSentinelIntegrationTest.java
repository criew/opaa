package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SpaceRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaMockedChatModelIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import io.opaa.test.SentinelScan;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deletion completeness of a chat: a unique marker goes into the question, the answer and the
 * generated title, and after the chat's deletion no column of any table of the schema holds it any
 * more ({@link SentinelScan}), except the columns of {@link #RETAINED}.
 */
@OpaaMockedChatModelIntegrationTest
class ChatDeletionSentinelIntegrationTest {

  /** Columns that keep the marker after the deletion by decision, with the reason; none today. */
  private static final Map<String, String> RETAINED = Map.of();

  @Autowired private ChatService chatService;
  @Autowired private ChatRepository chatRepository;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private ChatModel chatModel;
  @Autowired private ActiveChatModelResolver activeChatModelResolver;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Sentinel")).getId();
  }

  @AfterEach
  void tearDown() {
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void nothingOfADeletedChatStaysInAnyTable() {
    String sentinel = SentinelScan.newSentinel();
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("Titel " + sentinel)))));
    User author =
        new User(UUID.randomUUID().toString(), "test-issuer", "chat@example.com", "Chat Autorin");
    author.setOrganizationId(organizationId);
    UUID authorId = userRepository.save(author).getId();
    Space space = new Space("Fachbereich", null, false, authorId, organizationId);
    space.addMembership(SpaceMembership.ofUser(authorId, SpaceRole.MEMBER, organizationId));
    UUID spaceId = spaceRepository.save(space).getId();
    UUID chatId = chatService.createChat(spaceId, authorId, new ChatCreation()).getId();
    chatService.appendTurn(
        chatRepository.findById(chatId).orElseThrow(),
        "Frage zu " + sentinel,
        "Antwort zu " + sentinel,
        List.of());
    assertThat(SentinelScan.find(jdbcTemplate, sentinel))
        .as("the marker must first be stored where the deletion has to reach")
        .extracting(SentinelScan.Hit::qualifiedColumn)
        .contains("chat_messages.content");

    chatService.deleteChat(chatId, authorId);

    assertThat(SentinelScan.find(jdbcTemplate, sentinel))
        .extracting(SentinelScan.Hit::qualifiedColumn)
        .allSatisfy(column -> assertThat(RETAINED).containsKey(column));
  }
}
