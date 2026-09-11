package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.query.QueryProperties;
import io.opaa.query.answer.AnswerGenerationService;
import io.opaa.query.answer.CaffeineChatMemoryRepository;
import io.opaa.query.answer.ConversationWindowMessages;
import io.opaa.query.retrieval.RerankAvailability;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalState;
import io.opaa.query.retrieval.search.DecompositionContext;
import io.opaa.query.retrieval.search.QueryDecompositionService;
import io.opaa.query.retrieval.search.SubQueryDecompositionStage;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Binds the fixed points of the multi-turn measurement path to the production behaviour they claim
 * to describe (issue #1484) - the window width is already bound by being measured off the
 * production {@code ChatMemory} ({@link ConversationMemoryProfile#measuredFrom}), and these close
 * the same gap for the other two assumptions.
 *
 * <p>All three are <b>guards, not specifications</b>: production is free to change any of them, but
 * not silently. When the search window stops being cut where it is cut today, or an entrance into
 * the conversation window stops normalizing the answer text, one of these fails - and whoever makes
 * that change updates {@link ConversationMemoryProfile} and re-draws the conversation baseline.
 */
class ConversationMemoryProductionBindingTest {

  private static final String CITATION =
      "【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#0 | anwohnerparken.md】";

  /**
   * The search window is cut <b>inside the stage</b>, on the way to the decomposition - which is
   * why {@link ConversationMemoryProfile#searchWindowTurns} describes what this path measures at
   * all. The harness hands the whole conversation window to {@code
   * RetrievalContextFactory#contextFor}; were the cut made before that call instead, this path
   * would go past it and measure a window production never searches with.
   */
  @Test
  void theDecompositionSeesExactlyTheConfiguredSearchWindow() {
    QueryDecompositionService decomposition = mock(QueryDecompositionService.class);
    when(decomposition.decompose(any(DecompositionContext.class), anyInt()))
        .thenReturn(List.of("Teilfrage"));
    List<Message> window =
        List.of(
            new UserMessage("Frage 1?"),
            new AssistantMessage("Antwort 1."),
            new UserMessage("Frage 2?"),
            new AssistantMessage("Antwort 2."),
            new UserMessage("Frage 3?"),
            new AssistantMessage("Antwort 3."));
    QueryProperties properties = new QueryProperties(8, 25, 1.0, 0.3, true, 3, 2, true, 0, 20, 2);

    RetrievalContext context =
        new RetrievalContext(
            "Und bei Bedürftigkeit?",
            window,
            Set.of(UUID.randomUUID()),
            MetadataFilter.NONE,
            properties,
            RerankAvailability.SWITCHED_OFF);

    new SubQueryDecompositionStage(decomposition).apply(context, RetrievalState.initial());

    ArgumentCaptor<DecompositionContext> seen = ArgumentCaptor.forClass(DecompositionContext.class);
    verify(decomposition).decompose(seen.capture(), anyInt());
    assertThat(seen.getValue().searchWindow())
        .as(
            "the decomposition receives the last %d turns of the conversation window - a different "
                + "number of turns changes ConversationMemoryProfile.searchWindowTurns and "
                + "invalidates the committed conversation baseline",
            properties.searchWindowTurns())
        .isEqualTo(
            window.subList(window.size() - 2 * properties.searchWindowTurns(), window.size()));
  }

  /**
   * First entrance into the conversation window: after the answer. The window holds the answer
   * without its citation markers, while the response the caller persists keeps them - which is why
   * the harness may append its hand-written, marker-free short answer as-is and still take the path
   * production takes.
   */
  @Test
  void theAnswerEntersTheConversationWindowWithoutItsCitationMarkers() {
    String answerWithCitation = "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr. " + CITATION;
    ChatMemory chatMemory = productionShapedChatMemory();
    ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient.prompt().system(anyString()).messages(anyList()).call().chatResponse())
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage(answerWithCitation)))));
    ActiveChatModelResolver resolver = mock(ActiveChatModelResolver.class);
    when(resolver.resolveChatClient()).thenReturn(chatClient);
    String conversationId = "binding-test-" + UUID.randomUUID();

    ChatResponse response =
        new AnswerGenerationService(resolver, chatMemory)
            .generateAnswer(
                "Was kostet ein Anwohnerparkausweis?", List.of(), conversationId, List.of());

    assertThat(response.getResult().getOutput().getText())
        .as("the persisted answer keeps its markers - only the window's copy loses them")
        .isEqualTo(answerWithCitation);
    assertThat(chatMemory.get(conversationId))
        .as(
            "the window holds the question verbatim and the answer without markers - the shape the "
                + "harness reproduces by scripting marker-free short answers")
        .extracting(Message::getText)
        .containsExactly(
            "Was kostet ein Anwohnerparkausweis?",
            "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.");
  }

  /**
   * Second entrance: the reload on a cache miss. Both entrances normalize through the same {@code
   * CitationMarkers}, so the window a restarted process rebuilds from the database is the window
   * the running process held - the invariant the harness relies on when it builds a window from
   * scripted turns rather than from a persisted chat.
   */
  @Test
  void theReloadedConversationWindowMatchesTheOneTheRunningProcessHeld() {
    String answerWithCitation = "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr. " + CITATION;
    ChatMemory liveMemory = productionShapedChatMemory();
    ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient.prompt().system(anyString()).messages(anyList()).call().chatResponse())
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage(answerWithCitation)))));
    ActiveChatModelResolver resolver = mock(ActiveChatModelResolver.class);
    when(resolver.resolveChatClient()).thenReturn(chatClient);
    String conversationId = "binding-test-" + UUID.randomUUID();
    new AnswerGenerationService(resolver, liveMemory)
        .generateAnswer(
            "Was kostet ein Anwohnerparkausweis?", List.of(), conversationId, List.of());

    // What QueryService#seedConversationMemoryFromPersistedHistory rebuilds from the persisted
    // rows, which carry the markers: the same texts, or the same chat sends a different prompt
    // after a restart than before it.
    List<Message> reloaded =
        ConversationWindowMessages.reloaded(
            List.of(
                new UserMessage("Was kostet ein Anwohnerparkausweis?"),
                new AssistantMessage(answerWithCitation)));

    assertThat(reloaded)
        .extracting(Message::getText)
        .isEqualTo(liveMemory.get(conversationId).stream().map(Message::getText).toList());
  }

  private static ChatMemory productionShapedChatMemory() {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(new CaffeineChatMemoryRepository(new SimpleMeterRegistry()))
        .maxMessages(20)
        .build();
  }
}
