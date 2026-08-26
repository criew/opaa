package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.llm.ActiveChatModelResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Unit tests for {@link QueryDecompositionService#decompose}'s parsing and fallback behaviour
 * (#923) - the LLM call itself is stubbed via a mocked {@link ChatModel} wrapped in a real {@link
 * ChatClient}, exactly like {@code ChatTitleGenerationServiceTest} stubs the equivalent single-call
 * LLM pattern.
 */
class QueryDecompositionServiceTest {

  private final ChatModel chatModel = mock(ChatModel.class);
  private final ActiveChatModelResolver activeChatModelResolver =
      mock(ActiveChatModelResolver.class);
  private final QueryDecompositionService service =
      new QueryDecompositionService(activeChatModelResolver);

  private void stubChatModelResponse(String text) {
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    var response = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    when(chatModel.call(any(Prompt.class))).thenReturn(response);
  }

  @Test
  void oneLinePerSubQueryIsParsedIntoSeparateEntries() {
    stubChatModelResponse("Was kostet ein Personalausweis?\nWas kostet ein Führerschein?");

    List<String> subQueries = service.decompose("Kombifrage", List.of(), 3);

    assertThat(subQueries)
        .containsExactly("Was kostet ein Personalausweis?", "Was kostet ein Führerschein?");
  }

  @Test
  void aSingleTopicQuestionDecomposesToExactlyOneSubQuery() {
    stubChatModelResponse("Was kostet ein Personalausweis?");

    List<String> subQueries = service.decompose("Was kostet ein Personalausweis?", List.of(), 3);

    assertThat(subQueries).containsExactly("Was kostet ein Personalausweis?");
  }

  @Test
  void leadingBulletsAndNumberingAreStripped() {
    stubChatModelResponse("- Erste Frage\n2) Zweite Frage\n3. Dritte Frage");

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).containsExactly("Erste Frage", "Zweite Frage", "Dritte Frage");
  }

  @Test
  void blankLinesAreDropped() {
    stubChatModelResponse("Erste Frage\n\n   \nZweite Frage");

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).containsExactly("Erste Frage", "Zweite Frage");
  }

  @Test
  void moreLinesThanMaxSubQueriesAreTruncated() {
    stubChatModelResponse("Eins\nZwei\nDrei\nVier\nFünf");

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).containsExactly("Eins", "Zwei", "Drei");
  }

  @Test
  void duplicateLinesAreDeduplicated() {
    stubChatModelResponse("Frage A\nFrage A\nFrage B");

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).containsExactly("Frage A", "Frage B");
  }

  /** Unparsable output (blank/whitespace-only) is a decomposition failure - #923: no exception. */
  @Test
  void blankResponseFallsBackToAnEmptyList() {
    stubChatModelResponse("   \n  \n");

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).isEmpty();
  }

  @Test
  void noActiveChatModelFallsBackToAnEmptyListInsteadOfThrowing() {
    when(activeChatModelResolver.resolveChatClient())
        .thenThrow(new RuntimeException("kein aktives Chat-Modell konfiguriert"));

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).isEmpty();
  }

  @Test
  void anLlmCallFailureFallsBackToAnEmptyListInsteadOfThrowing() {
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    doThrow(new RuntimeException("LLM nicht erreichbar")).when(chatModel).call(any(Prompt.class));

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).isEmpty();
  }
}
