package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ChatRole;
import io.opaa.common.TestFeatureFlags;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.metadata.DocumentTypeVocabularyRepository;
import io.opaa.metadata.LibraryMetadataFieldRepository;
import io.opaa.metadata.LibraryMetadataFieldValueRepository;
import io.opaa.metadata.MetadataFilterValidator;
import io.opaa.observability.ChatMetrics;
import io.opaa.space.ChatAutoCleanupSettings;
import io.opaa.space.SpaceAccessPolicy;
import io.opaa.space.SpaceAssetAssociationRepository;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** How the chat's latest turn ended, as a follow-up agent run reads it (plan confirmation). */
class ChatServiceLatestAgentRunTest {

  private final UUID chatId = UUID.randomUUID();
  private final ObjectMapper mapper = JsonMapper.builder().build();
  private ChatMessageRepository messages;
  private ChatService service;

  @BeforeEach
  void setUp() {
    messages = mock(ChatMessageRepository.class);
    service =
        new ChatService(
            mock(ChatRepository.class),
            messages,
            mock(SpaceRepository.class),
            mock(SpaceAccessPolicy.class),
            mock(SpaceAssetAssociationRepository.class),
            mock(SpaceAssetAssociationService.class),
            mock(LibraryAccessService.class),
            mapper,
            mock(ChatMessageWriter.class),
            mock(ChatTitleGenerationService.class),
            new MetadataFilterValidator(
                mock(DocumentTypeVocabularyRepository.class),
                mock(LibraryMetadataFieldRepository.class),
                mock(LibraryMetadataFieldValueRepository.class),
                TestFeatureFlags.allOn()),
            mock(ChatNoteService.class),
            mock(ChatPersonalMarkRepository.class),
            mock(ChatFullTextSearch.class),
            mock(ChatMetrics.class),
            mock(ChatAutoCleanupSettings.class),
            mock(ChatAttachmentService.class),
            mock(AgentRevisions.class),
            Clock.systemUTC());
  }

  @Test
  void theLatestAnswerOfAnAgentNamesHowItsRunEnded() {
    AgentRunRecord plan =
        new AgentRunRecord(
            UUID.randomUUID(),
            AgentRunRecord.EndState.PLAN,
            List.of(),
            UUID.randomUUID(),
            "Recherche");
    ChatMessage answer = new ChatMessage(chatId, 2, ChatRole.ASSISTANT, "1. Plan", null);
    answer.setAgentRun(mapper.writeValueAsString(plan));
    latest(answer);

    assertThat(service.latestAgentRun(chatId)).contains(plan);
  }

  @Test
  void anAnswerWithoutAgentAQuestionOrAnEmptyChatHaveNone() {
    latest(new ChatMessage(chatId, 2, ChatRole.ASSISTANT, "Antwort", null));
    assertThat(service.latestAgentRun(chatId)).isEmpty();

    latest(new ChatMessage(chatId, 3, ChatRole.USER, "Frage", null));
    assertThat(service.latestAgentRun(chatId)).isEmpty();

    when(messages.findByChatIdOrderBySequenceDesc(eq(chatId), any(Limit.class)))
        .thenReturn(List.of());
    assertThat(service.latestAgentRun(chatId)).isEmpty();
  }

  @Test
  void thePreviousAgentRunIsTheOneOfTheAssistantAnswerBeforeTheLatest() {
    AgentRunRecord first =
        new AgentRunRecord(
            UUID.randomUUID(),
            AgentRunRecord.EndState.PLAN,
            List.of(),
            UUID.randomUUID(),
            "Recherche");
    ChatMessage older = new ChatMessage(chatId, 1, ChatRole.ASSISTANT, "1. Plan", null);
    older.setAgentRun(mapper.writeValueAsString(first));
    ChatMessage question = new ChatMessage(chatId, 2, ChatRole.USER, "Bestätigt", null);
    ChatMessage newest = new ChatMessage(chatId, 3, ChatRole.ASSISTANT, "1. Plan neu", null);
    when(messages.findByChatIdOrderBySequenceDesc(eq(chatId), any(Limit.class)))
        .thenReturn(List.of(newest, question, older));

    assertThat(service.previousAgentRun(chatId)).contains(first);

    latest(newest);
    assertThat(service.previousAgentRun(chatId)).isEmpty();
  }

  private void latest(ChatMessage message) {
    when(messages.findByChatIdOrderBySequenceDesc(eq(chatId), any(Limit.class)))
        .thenReturn(List.of(message));
  }
}
