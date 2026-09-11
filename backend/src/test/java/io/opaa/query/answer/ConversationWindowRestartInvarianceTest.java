package io.opaa.query.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.llm.ActiveChatModelResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The load-bearing invariant of the conversation window (#1486,
 * docs/features/conversation-memory.md): the same chat sends the same prompt before and after a
 * restart or a cache eviction. Both entrances into the window - the answer just generated and the
 * persisted history read back - go through {@link ConversationWindowMessages}, so a warm cache and
 * a cold one produce the identical message list.
 */
@ExtendWith(MockitoExtension.class)
class ConversationWindowRestartInvarianceTest {

  private static final String QUESTION = "Was kostet ein Anwohnerparkausweis?";
  private static final String FOLLOW_UP = "Und bei Bedürftigkeit?";
  private static final String PERSISTED_ANSWER =
      "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr. "
          + "【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#0 | anwohnerparken.md】";

  @Mock private ChatModel chatModel;
  @Mock private ActiveChatModelResolver activeChatModelResolver;

  private ChatMemory chatMemory;
  private AnswerGenerationService answerGenerationService;

  @BeforeEach
  void setUp() {
    lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    lenient()
        .when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    chatMemory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new CaffeineChatMemoryRepository(new SimpleMeterRegistry()))
            .maxMessages(20)
            .build();
    answerGenerationService = new AnswerGenerationService(activeChatModelResolver, chatMemory);
  }

  @Test
  void theFollowUpPromptIsIdenticalBeforeAndAfterACacheEviction() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(answer(PERSISTED_ANSWER), answer("Ermäßigt."), answer("Ermäßigt."));
    String conversationId = "invariance-" + UUID.randomUUID();

    answerGenerationService.generateAnswer(QUESTION, List.of(), conversationId);
    answerGenerationService.generateAnswer(FOLLOW_UP, List.of(), conversationId);

    // The restart: the process-local cache is gone, the persisted rows - markers and all - are not.
    chatMemory.clear(conversationId);
    chatMemory.add(
        conversationId,
        ConversationWindowMessages.reloaded(
            List.of(new UserMessage(QUESTION), new AssistantMessage(PERSISTED_ANSWER))));
    answerGenerationService.generateAnswer(FOLLOW_UP, List.of(), conversationId);

    ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(3)).call(prompts.capture());
    assertThat(texts(prompts.getAllValues().get(2)))
        .as(
            "the follow-up prompt after a cache eviction is the one the warm cache produced - "
                + "otherwise the same chat is answered differently after a restart")
        .isEqualTo(texts(prompts.getAllValues().get(1)));
  }

  /** The window that a restart rebuilds carries no citation markers either. */
  @Test
  void theReloadedWindowCarriesNoCitationMarkers() {
    List<Message> reloaded =
        ConversationWindowMessages.reloaded(
            List.of(new UserMessage(QUESTION), new AssistantMessage(PERSISTED_ANSWER)));

    assertThat(reloaded)
        .extracting(Message::getText)
        .containsExactly(QUESTION, "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.");
  }

  /** A question is the person's own wording and is never rewritten on its way into the window. */
  @Test
  void aQuestionIsCarriedIntoTheWindowVerbatim() {
    List<Message> reloaded =
        ConversationWindowMessages.reloaded(
            List.of(new UserMessage("Was bedeutet 【source: x#1 | y.md】 in Ihrer Antwort?")));

    assertThat(reloaded)
        .extracting(Message::getText)
        .containsExactly("Was bedeutet 【source: x#1 | y.md】 in Ihrer Antwort?");
  }

  /**
   * The same rule on the reload path: a persisted answer that is nothing but a marker contributes
   * no window message either, so the reloaded window matches the one the running process held.
   */
  @Test
  void aPersistedAnswerThatIsOnlyAMarkerContributesNoWindowMessage() {
    List<Message> reloaded =
        ConversationWindowMessages.reloaded(
            List.of(
                new UserMessage(QUESTION),
                new AssistantMessage("【source: doc-1#0 | a.md】"),
                new UserMessage(FOLLOW_UP)));

    assertThat(reloaded).extracting(Message::getText).containsExactly(QUESTION, FOLLOW_UP);
  }

  private static ChatResponse answer(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static List<String> texts(Prompt prompt) {
    return prompt.getInstructions().stream().map(Message::getText).toList();
  }
}
