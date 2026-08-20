package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 */
@ExtendWith(MockitoExtension.class)
class ChatTitleGenerationServiceTest {

  @Mock private ChatModel chatModel;
  @Mock private ChatRepository chatRepository;

  private ChatTitleGenerationService service;

  @BeforeEach
  void setUp() {
    // Spring AI 2.0 merges ChatModel.getOptions() into every request; a bare mock returns null.
    lenient().when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    ChatClient.Builder builder = ChatClient.builder(chatModel);
    service = new ChatTitleGenerationService(builder, chatRepository);
  }

  private Chat generatedTitleChat() {
    return new Chat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, true, Set.of());
  }

  @Test
  void generateTitleAsyncAppliesTheSanitizedLlmTitle() {
    Chat chat = generatedTitleChat();
    when(chatRepository.findById(chat.getId())).thenReturn(Optional.of(chat));
    var chatResponse =
        new ChatResponse(
            List.of(new Generation(new AssistantMessage("\"Rückstellung Altlastensanierung\""))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    service.generateTitleAsync(
        chat.getId(), "Wie hoch ist die Rückstellung?", "Die Rückstellung beträgt 42.000 EUR.");

    assertThat(chat.getTitle()).isEqualTo("Rückstellung Altlastensanierung");
    verify(chatRepository).save(chat);
  }

  @Test
  void generateTitleAsyncSendsQuestionAndAnswerInTheGermanPrompt() {
    Chat chat = generatedTitleChat();
    when(chatRepository.findById(chat.getId())).thenReturn(Optional.of(chat));
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Titel"))));
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    when(chatModel.call(promptCaptor.capture())).thenReturn(chatResponse);

    service.generateTitleAsync(chat.getId(), "Meine Frage", "Meine Antwort");

    String promptText = promptCaptor.getValue().getInstructions().getFirst().getText();
    assertThat(promptText).contains("Meine Frage");
    assertThat(promptText).contains("Meine Antwort");
    assertThat(promptText).contains("deutschen Titel");
  }

  @Test
  void generateTitleAsyncNeverOverwritesACustomTitle() {
    Chat chat =
        new Chat(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Mein Titel", true, Set.of());
    when(chatRepository.findById(chat.getId())).thenReturn(Optional.of(chat));
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("LLM-Titel"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    service.generateTitleAsync(chat.getId(), "Frage", "Antwort");

    assertThat(chat.getTitle()).isEqualTo("Mein Titel");
    verify(chatRepository, never()).save(any());
  }

  /**
   * #557 acceptance criterion: an LLM failure must never surface - the fallback title {@code
   * ChatService#appendTurn} already committed synchronously (simulated here via {@link
   * Chat#deriveTitleFromFirstQuestionIfAbsent}) stays exactly as is.
   */
  @Test
  void generateTitleAsyncKeepsTheFallbackTitleWhenTheLlmCallFails() {
    Chat chat = generatedTitleChat();
    chat.deriveTitleFromFirstQuestionIfAbsent("Fallback-Titel");
    when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM nicht erreichbar"));

    service.generateTitleAsync(chat.getId(), "Frage", "Antwort");

    assertThat(chat.getTitle()).isEqualTo("Fallback-Titel");
    verifyNoInteractions(chatRepository);
  }

  @Test
  void generateTitleAsyncKeepsTheFallbackTitleWhenTheLlmReturnsOnlyPunctuation() {
    Chat chat = generatedTitleChat();
    chat.deriveTitleFromFirstQuestionIfAbsent("Fallback-Titel");
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("\"\"\"."))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    service.generateTitleAsync(chat.getId(), "Frage", "Antwort");

    assertThat(chat.getTitle()).isEqualTo("Fallback-Titel");
    verifyNoInteractions(chatRepository);
  }

  @Test
  void generateTitleAsyncDiscardsTheResultWhenTheChatWasDeletedInTheMeantime() {
    UUID chatId = UUID.randomUUID();
    when(chatRepository.findById(chatId)).thenReturn(Optional.empty());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Titel"))));
    when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

    service.generateTitleAsync(chatId, "Frage", "Antwort");

    verify(chatRepository, never()).save(any());
  }
}
