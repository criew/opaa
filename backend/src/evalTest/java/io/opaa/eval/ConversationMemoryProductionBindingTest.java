package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.opaa.query.retrieval.RerankAvailability;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalState;
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
 * Binds two fixed points of the multi-turn measurement path to the production behaviour they claim
 * to describe (issue #1484) - the window width is already bound by being measured off the
 * production {@code ChatMemory} ({@link ConversationMemoryProfile#measuredFrom}), and these two
 * close the same gap for the other two assumptions.
 *
 * <p>Both are <b>guards, not specifications</b>: production is free to change either, but not
 * silently. When the sub-question decomposition starts seeing a narrower search window, or the
 * conversation window starts carrying normalized answer text, one of these fails - and whoever
 * makes that change updates {@link ConversationMemoryProfile} and re-draws the conversation
 * baseline. Without them the baseline would keep claiming {@code searchWindowTurns = 0} while the
 * run measured something else, and the specification's <b>expected</b> drop of {@code
 * constraint_carryover} would be booked as a regression.
 */
class ConversationMemoryProductionBindingTest {

  /**
   * The whole conversation window reaches the decomposition, unnarrowed - which is what {@link
   * ConversationMemoryProfile#SEARCH_WINDOW_WHOLE_CONVERSATION_WINDOW} records as this run's
   * search-window fixed point.
   */
  @Test
  void theDecompositionSeesTheWholeConversationWindow() {
    QueryDecompositionService decomposition = mock(QueryDecompositionService.class);
    when(decomposition.decompose(anyString(), anyList(), anyInt()))
        .thenReturn(List.of("Teilfrage"));
    List<Message> window =
        List.of(
            new UserMessage("Frage 1?"),
            new AssistantMessage("Antwort 1."),
            new UserMessage("Frage 2?"),
            new AssistantMessage("Antwort 2."),
            new UserMessage("Frage 3?"),
            new AssistantMessage("Antwort 3."));

    RetrievalContext context =
        new RetrievalContext(
            "Und bei Bedürftigkeit?",
            window,
            Set.of(UUID.randomUUID()),
            MetadataFilter.NONE,
            new QueryProperties(8, 25, 1.0, 0.3, true, 3, 2, true, 0),
            RerankAvailability.SWITCHED_OFF);

    new SubQueryDecompositionStage(decomposition).apply(context, RetrievalState.initial());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Message>> seenHistory = ArgumentCaptor.forClass(List.class);
    verify(decomposition).decompose(anyString(), seenHistory.capture(), anyInt());
    assertThat(seenHistory.getValue())
        .as(
            "the decomposition receives the conversation window unnarrowed - narrowing it changes "
                + "ConversationMemoryProfile.searchWindowTurns and invalidates the committed "
                + "conversation baseline")
        .isEqualTo(context.conversationHistory());
  }

  /**
   * The conversation window carries the answer text verbatim, citation markers included - which is
   * why the harness may append the hand-written short answer unchanged and still take the same path
   * production takes.
   */
  @Test
  void theConversationWindowCarriesTheAnswerTextUnchanged() {
    String answerWithCitation =
        "Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr. "
            + "【source: 3fa85f64-5717-4562-b3fc-2c963f66afa6#0 | anwohnerparken.md】";
    ChatMemory chatMemory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new CaffeineChatMemoryRepository(new SimpleMeterRegistry()))
            .maxMessages(20)
            .build();
    ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    when(chatClient.prompt().system(anyString()).messages(anyList()).call().chatResponse())
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage(answerWithCitation)))));
    ActiveChatModelResolver resolver = mock(ActiveChatModelResolver.class);
    when(resolver.resolveChatClient()).thenReturn(chatClient);
    String conversationId = "binding-test-" + UUID.randomUUID();

    new AnswerGenerationService(resolver, chatMemory)
        .generateAnswer("Was kostet ein Anwohnerparkausweis?", List.of(), conversationId);

    assertThat(chatMemory.get(conversationId))
        .as(
            "the window holds question and answer verbatim - once production normalizes the answer "
                + "on its way in, the harness has to take that same path instead of appending the "
                + "scripted answer as-is")
        .extracting(Message::getText)
        .containsExactly("Was kostet ein Anwohnerparkausweis?", answerWithCitation);
  }
}
