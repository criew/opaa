package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.observability.QueryMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
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
  private final QueryMetrics metrics = mock(QueryMetrics.class);
  private final QueryDecompositionService service =
      new QueryDecompositionService(activeChatModelResolver, metrics);

  private void stubChatModelResponse(String text) {
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    var response = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    when(chatModel.call(any(Prompt.class))).thenReturn(response);
  }

  private String capturedSystemPrompt() {
    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    return prompt.getValue().getInstructions().stream()
        .filter(message -> message.getMessageType() == MessageType.SYSTEM)
        .map(Message::getText)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void oneLinePerSubQueryIsParsedIntoSeparateEntries() {
    stubChatModelResponse("Was kostet ein Personalausweis?\nWas kostet ein Führerschein?");

    List<String> subQueries =
        service.decompose("Was kostet ein Personalausweis und ein Führerschein?", List.of(), 3);

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
    stubChatModelResponse("Frage eins\nFrage zwei\nFrage drei\nFrage vier\nFrage fünf");

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).containsExactly("Frage eins", "Frage zwei", "Frage drei");
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
    verify(metrics).recordDegenerateDecomposition();
  }

  @Test
  void noActiveChatModelFallsBackToAnEmptyListInsteadOfThrowing() {
    when(activeChatModelResolver.resolveChatClient())
        .thenThrow(new RuntimeException("kein aktives Chat-Modell konfiguriert"));

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordFailedDecomposition();
  }

  @Test
  void anLlmCallFailureFallsBackToAnEmptyListInsteadOfThrowing() {
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    doThrow(new RuntimeException("LLM nicht erreichbar")).when(chatModel).call(any(Prompt.class));

    List<String> subQueries = service.decompose("Frage", List.of(), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordFailedDecomposition();
  }

  /**
   * Regression guard for #1254: the system prompt must not demonstrate a search query, only
   * describe the output format - a small instruct model returns a demonstrated sentence verbatim. A
   * demonstrated German question would carry a question mark or quotation marks; the prompt carries
   * neither.
   */
  @Test
  void theSystemPromptContainsNoExampleSentence() {
    stubChatModelResponse("Was kostet ein Personalausweis?");

    service.decompose("Was kostet ein Personalausweis?", List.of(), 3);

    assertThat(capturedSystemPrompt()).doesNotContain("?").doesNotContain("\"").contains("1 bis 3");
  }

  /**
   * Regression guard for #1254: an output that shares no word with the question replaced the
   * question instead of restating it. Searching for it discards the user's request; the run falls
   * back to the undecomposed question, loudly.
   */
  @Test
  void anOutputUnrelatedToTheQuestionIsDiscarded() {
    stubChatModelResponse("und was kostet das?");

    List<String> subQueries =
        service.decompose("Wer wird von der Gebühr befreit, wenn er bedürftig ist?", List.of(), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordDegenerateDecomposition();
  }

  @Test
  void onlyTheUnrelatedSubQueriesAreDroppedWhenOthersRemain() {
    stubChatModelResponse("Gebührenbefreiung bei Bedürftigkeit\nund was kostet das?");

    List<String> subQueries =
        service.decompose("Wer wird von der Gebühr befreit, wenn er bedürftig ist?", List.of(), 3);

    assertThat(subQueries).containsExactly("Gebührenbefreiung bei Bedürftigkeit");
    verifyNoInteractions(metrics);
  }

  /** A follow-up is related to the conversation it resolves against, not to its own wording. */
  @Test
  void aSubQueryRelatedOnlyToTheConversationHistoryIsKept() {
    stubChatModelResponse("Was kostet ein Personalausweis?");
    List<Message> history = List.of(new UserMessage("Wo beantrage ich einen Personalausweis?"));

    List<String> subQueries = service.decompose("Und was kostet das?", history, 3);

    assertThat(subQueries).containsExactly("Was kostet ein Personalausweis?");
  }

  /** A question made up entirely of short function words offers nothing to relate against. */
  @Test
  void aQuestionWithoutAnchorWordsSkipsTheRelatednessCheck() {
    stubChatModelResponse("Zuständige Stelle für die Anmeldung");

    List<String> subQueries = service.decompose("Wer tut das?", List.of(), 3);

    assertThat(subQueries).containsExactly("Zuständige Stelle für die Anmeldung");
  }
}
