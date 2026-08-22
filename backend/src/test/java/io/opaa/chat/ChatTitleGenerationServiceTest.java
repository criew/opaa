package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Unit-level coverage for #557's LLM title generation: since {@code @Async} only takes effect
 * through a Spring AOP proxy, calling {@link ChatTitleGenerationService#generateTitleAsync}
 * directly on a plain, hand-constructed instance (as every test here does) runs synchronously on
 * the calling thread - deterministic, no {@code Awaitility} needed. {@link
 * io.opaa.query.QueryIntegrationTest} and {@code ChatServiceIntegrationTest} cover the genuinely
 * asynchronous, Spring-managed path end to end.
 *
 * <p>#561 review, finding 1/2: the CUSTOM-title guard and the write itself are no longer this
 * class's own load-check-{@code save()} cycle - they are one atomic {@code
 * ChatRepository#applyGeneratedTitleIfGenerated} {@code UPDATE}, so this class has nothing left to
 * unit-test about *whether* a CUSTOM title is protected (that guarantee now lives in the database
 * query itself, proved by {@code ChatServiceIntegrationTest}'s race tests and {@code
 * Migration034AddChatTitleSourceTest}) - only that it correctly calls that method with the
 * sanitized title, and handles "0 rows updated" (rejected, or the chat no longer exists) without
 * throwing.
 */
@ExtendWith(MockitoExtension.class)
class ChatTitleGenerationServiceTest {

  private static final UUID CHAT_ID = UUID.randomUUID();

  @Mock private ChatModel chatModel;
  @Mock private ChatRepository chatRepository;
  @Mock private ActiveChatModelResolver activeChatModelResolver;

  private ChatTitleGenerationService service;

  @BeforeEach
  void setUp() {
    // Spring AI 2.0 merges ChatModel.getOptions() into every request; a bare mock returns null.
    lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    lenient()
        .when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    service = new ChatTitleGenerationService(activeChatModelResolver, chatRepository);
  }

  private static ChatResponse chatResponseWith(String assistantText) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(assistantText))));
  }

  @Test
  void generateTitleAsyncAppliesTheSanitizedLlmTitleViaTheAtomicUpdate() {
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(chatResponseWith("\"Rückstellung Altlastensanierung\""));
    when(chatRepository.applyGeneratedTitleIfGenerated(CHAT_ID, "Rückstellung Altlastensanierung"))
        .thenReturn(1);

    service.generateTitleAsync(
        CHAT_ID, "Wie hoch ist die Rückstellung?", "Die Rückstellung beträgt 42.000 EUR.");

    verify(chatRepository)
        .applyGeneratedTitleIfGenerated(CHAT_ID, "Rückstellung Altlastensanierung");
  }

  @Test
  void generateTitleAsyncSendsQuestionAndAnswerInTheGermanPrompt() {
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponseWith("Titel"));

    service.generateTitleAsync(CHAT_ID, "Meine Frage", "Meine Antwort");

    String promptText = promptCaptor.getValue().getInstructions().getFirst().getText();
    assertThat(promptText).contains("Meine Frage");
    assertThat(promptText).contains("Meine Antwort");
    assertThat(promptText).contains("deutschen Titel");
  }

  /**
   * #561 review, finding 1/2: the CUSTOM guard is now the atomic {@code UPDATE}'s {@code WHERE}
   * clause, not anything this class decides - this only proves the service does not throw or retry
   * when the repository reports the update matched no row (0), which is exactly what a CUSTOM title
   * - or a chat deleted in the meantime - looks like from here.
   */
  @Test
  void generateTitleAsyncDoesNotThrowWhenTheAtomicUpdateMatchesNoRow() {
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponseWith("LLM-Titel"));
    when(chatRepository.applyGeneratedTitleIfGenerated(eq(CHAT_ID), any())).thenReturn(0);

    assertThatCode(() -> service.generateTitleAsync(CHAT_ID, "Frage", "Antwort"))
        .doesNotThrowAnyException();
  }

  /**
   * #557 acceptance criterion: an LLM failure must never surface - the repository is never even
   * called, so whatever fallback title {@code ChatService#appendTurn} already committed
   * synchronously stays exactly as is.
   */
  @Test
  void generateTitleAsyncNeverCallsTheRepositoryWhenTheLlmCallFails() {
    when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM nicht erreichbar"));

    service.generateTitleAsync(CHAT_ID, "Frage", "Antwort");

    verifyNoInteractions(chatRepository);
  }

  @Test
  void generateTitleAsyncNeverCallsTheRepositoryWhenTheLlmReturnsOnlyPunctuation() {
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponseWith("\"\"\"."));

    service.generateTitleAsync(CHAT_ID, "Frage", "Antwort");

    verifyNoInteractions(chatRepository);
  }
}
