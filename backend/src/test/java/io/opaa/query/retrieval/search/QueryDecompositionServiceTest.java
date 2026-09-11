package io.opaa.query.retrieval.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.observability.QueryMetrics;
import io.opaa.query.ConversationNoteBlock;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
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

  private Prompt capturedPrompt() {
    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    return prompt.getValue();
  }

  private String capturedSystemPrompt() {
    return systemTextOf(capturedPrompt());
  }

  @Test
  void oneLinePerSubQueryIsParsedIntoSeparateEntries() {
    stubChatModelResponse("Was kostet ein Personalausweis?\nWas kostet ein Führerschein?");

    List<String> subQueries =
        service.decompose(
            DecompositionContext.of(
                "Was kostet ein Personalausweis und ein Führerschein?", List.of()),
            3);

    assertThat(subQueries)
        .containsExactly("Was kostet ein Personalausweis?", "Was kostet ein Führerschein?");
  }

  @Test
  void aSingleTopicQuestionDecomposesToExactlyOneSubQuery() {
    stubChatModelResponse("Was kostet ein Personalausweis?");

    List<String> subQueries =
        service.decompose(DecompositionContext.of("Was kostet ein Personalausweis?", List.of()), 3);

    assertThat(subQueries).containsExactly("Was kostet ein Personalausweis?");
  }

  @Test
  void leadingBulletsAndNumberingAreStripped() {
    stubChatModelResponse("- Erste Frage\n2) Zweite Frage\n3. Dritte Frage");

    List<String> subQueries = service.decompose(DecompositionContext.of("Frage", List.of()), 3);

    assertThat(subQueries).containsExactly("Erste Frage", "Zweite Frage", "Dritte Frage");
  }

  @Test
  void blankLinesAreDropped() {
    stubChatModelResponse("Erste Frage\n\n   \nZweite Frage");

    List<String> subQueries = service.decompose(DecompositionContext.of("Frage", List.of()), 3);

    assertThat(subQueries).containsExactly("Erste Frage", "Zweite Frage");
  }

  @Test
  void moreLinesThanMaxSubQueriesAreTruncated() {
    stubChatModelResponse("Frage eins\nFrage zwei\nFrage drei\nFrage vier\nFrage fünf");

    List<String> subQueries = service.decompose(DecompositionContext.of("Frage", List.of()), 3);

    assertThat(subQueries).containsExactly("Frage eins", "Frage zwei", "Frage drei");
  }

  @Test
  void duplicateLinesAreDeduplicated() {
    stubChatModelResponse("Frage A\nFrage A\nFrage B");

    List<String> subQueries = service.decompose(DecompositionContext.of("Frage", List.of()), 3);

    assertThat(subQueries).containsExactly("Frage A", "Frage B");
  }

  /** Unparsable output (blank/whitespace-only) is a decomposition failure - #923: no exception. */
  @Test
  void blankResponseFallsBackToAnEmptyList() {
    stubChatModelResponse("   \n  \n");

    List<String> subQueries = service.decompose(DecompositionContext.of("Frage", List.of()), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordFailedDecomposition();
  }

  @Test
  void noActiveChatModelFallsBackToAnEmptyListInsteadOfThrowing() {
    when(activeChatModelResolver.resolveChatClient())
        .thenThrow(new RuntimeException("kein aktives Chat-Modell konfiguriert"));

    List<String> subQueries = service.decompose(DecompositionContext.of("Frage", List.of()), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordFailedDecomposition();
  }

  @Test
  void anLlmCallFailureFallsBackToAnEmptyListInsteadOfThrowing() {
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    doThrow(new RuntimeException("LLM nicht erreichbar")).when(chatModel).call(any(Prompt.class));

    List<String> subQueries = service.decompose(DecompositionContext.of("Frage", List.of()), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordFailedDecomposition();
  }

  /**
   * Regression guard for #1254: the system prompt describes the output format, it never
   * demonstrates a search query - a small instruct model returns a demonstrated sentence verbatim.
   * The prompt is purely instructional, so both markers of a demonstrated sentence are forbidden
   * outright: a quoted string and a question mark. A deliberate approximation - it also rules out a
   * legitimate question mark, and an example without punctuation would slip past it, which is what
   * the "Beispiel" check covers.
   */
  @Test
  void theSystemPromptDemonstratesNoSearchQuery() {
    stubChatModelResponse("Was kostet ein Personalausweis?");

    service.decompose(DecompositionContext.of("Was kostet ein Personalausweis?", List.of()), 3);

    assertThat(capturedSystemPrompt())
        .doesNotContain("?")
        .doesNotContain("\"")
        .doesNotContain("Beispiel")
        .contains("1 bis 3");
  }

  /**
   * Regression guard for #923/#1254: the rule that resolves a conversation-relative question
   * survived the removal of its example, and the conversation history still reaches the model.
   */
  @Test
  void theSystemPromptKeepsTheFollowUpRuleAndTheHistoryReachesTheModel() {
    stubChatModelResponse("Was kostet ein Personalausweis?");
    List<Message> history = List.of(new UserMessage("Wo beantrage ich einen Personalausweis?"));

    List<String> subQueries =
        service.decompose(DecompositionContext.of("Und was kostet das dann?", history), 3);

    assertThat(capturedSystemPrompt()).contains("rückverweisende").contains("Gesprächsverlauf");
    assertThat(capturedPrompt().getInstructions())
        .extracting(Message::getText)
        .contains("Wo beantrage ich einen Personalausweis?", "Und was kostet das dann?");
    assertThat(subQueries).containsExactly("Was kostet ein Personalausweis?");
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
        service.decompose(
            DecompositionContext.of(
                "Wer wird von der Gebühr befreit, wenn er bedürftig ist?", List.of()),
            3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordDegenerateDecomposition();
  }

  /**
   * Regression guard for #1254: all or nothing. Keeping the related remainder would search for one
   * topic of a two-topic question and drop the other - worse than the undecomposed question, which
   * carries both. The two dropped lines are the substring check's known blind spots (umlaut,
   * composition), which is exactly why the remainder must not be trusted.
   */
  @Test
  void oneUnrelatedSubQueryDiscardsTheWholeDecomposition() {
    stubChatModelResponse("Ausleihfrist für Bücher\nMahngebühr Bibliothek\nAusleihfrist Buch");

    List<String> subQueries =
        service.decompose(
            DecompositionContext.of(
                "Wie lange darf ich Bücher ausleihen und was kostet eine Mahnung?", List.of()),
            3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordPrunedDecomposition();
  }

  /**
   * Regression guard for #1254: docs/features/security-and-compliance.md keeps questions and search
   * terms out of the application log. The fallback must therefore be countable, not readable.
   */
  @Test
  void theFallbackLogsCountsButNoUserContent() {
    stubChatModelResponse("und was kostet das?");

    List<ILoggingEvent> events =
        captureWhile(
            () ->
                service.decompose(
                    DecompositionContext.of(
                        "Wer wird von der Gebühr befreit, wenn er bedürftig ist?", List.of()),
                    3));

    assertThat(events).isNotEmpty();
    assertThat(events)
        .allSatisfy(
            event ->
                assertThat(event.getFormattedMessage())
                    .doesNotContain("Gebühr")
                    .doesNotContain("bedürftig")
                    .doesNotContain("kostet"));
    assertThat(events)
        .anySatisfy(
            event -> assertThat(event.getFormattedMessage()).contains("1 of 1 sub-queries"));
  }

  /** A follow-up is related to the conversation it resolves against, not to its own wording. */
  @Test
  void aSubQueryRelatedOnlyToTheConversationHistoryIsKept() {
    stubChatModelResponse("Was kostet ein Personalausweis?");
    List<Message> history = List.of(new UserMessage("Wo beantrage ich einen Personalausweis?"));

    List<String> subQueries =
        service.decompose(
            DecompositionContext.of("Und was kostet das dann insgesamt?", history), 3);

    assertThat(subQueries).containsExactly("Was kostet ein Personalausweis?");
  }

  /**
   * Regression guard for #1254: the skip rule reads question <b>and</b> history. A short follow-up
   * carries almost no anchor of its own, so judging it on the question alone would switch the check
   * off in exactly the multi-turn case - the conversation it resolves into supplies the anchors.
   */
  @Test
  void aShortFollowUpIsStillCheckedAgainstTheConversationHistory() {
    stubChatModelResponse("Öffnungszeiten der Stadtbibliothek");
    List<Message> history =
        List.of(
            new UserMessage("Wo beantrage ich einen Personalausweis?"),
            new AssistantMessage("Der Personalausweis wird im Bürgeramt beantragt."));

    List<String> subQueries =
        service.decompose(DecompositionContext.of("Und was kostet das?", history), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordDegenerateDecomposition();
  }

  /** A question made up entirely of short function words offers nothing to relate against. */
  @Test
  void aQuestionWithoutAnchorWordsSkipsTheRelatednessCheck() {
    stubChatModelResponse("Zuständige Stelle für die Anmeldung");

    List<String> subQueries =
        service.decompose(DecompositionContext.of("Wer tut das?", List.of()), 3);

    assertThat(subQueries).containsExactly("Zuständige Stelle für die Anmeldung");
    verifyNoInteractions(metrics);
  }

  /**
   * A script without word separators collapses into a single token, so nothing could ever look
   * related - the check steps aside instead of discarding every decomposition.
   */
  @Test
  void aQuestionInAScriptWithoutWordBoundariesSkipsTheRelatednessCheck() {
    stubChatModelResponse("护照申请材料\n护照办理地点");

    List<String> subQueries =
        service.decompose(DecompositionContext.of("我想知道办理护照需要哪些材料", List.of()), 3);

    assertThat(subQueries).containsExactly("护照申请材料", "护照办理地点");
    verifyNoInteractions(metrics);
  }

  private static List<ILoggingEvent> captureWhile(Runnable action) {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(QueryDecompositionService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
      return List.copyOf(appender.list);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /**
   * The case the anchor space exists for (#1486): "Wie lange dauert das?" resolved into
   * "Bearbeitungsdauer für den Anwohnerparkausweis" shares no anchor with the question - "dauert"
   * is not a substring of "Bearbeitungsdauer" and the belt is deliberately no stemmer - but it
   * shares one with the previous turn in the search window.
   */
  @Test
  void aNominalPhraseResolvedFromThePreviousTurnIsKept() {
    stubChatModelResponse("Bearbeitungsdauer für den Anwohnerparkausweis");
    List<Message> searchWindow =
        List.of(
            new UserMessage("Was kostet ein Anwohnerparkausweis?"),
            new AssistantMessage("Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr."));

    List<String> subQueries =
        service.decompose(DecompositionContext.of("Wie lange dauert das?", searchWindow), 3);

    assertThat(subQueries).containsExactly("Bearbeitungsdauer für den Anwohnerparkausweis");
    verifyNoInteractions(metrics);
  }

  /**
   * The other half of the same rule: a topic that is no longer in the search window can no longer
   * legitimize a sub-query. Whoever cuts the window decides how far back that reaches - see {@link
   * SubQueryDecompositionStage}.
   */
  @Test
  void aSubQueryAboutATopicOutsideTheSearchWindowIsDiscarded() {
    stubChatModelResponse("Bearbeitungsdauer für den Anwohnerparkausweis");
    List<Message> searchWindow =
        List.of(
            new UserMessage("Wo finde ich das Formular für die Hundesteuer?"),
            new AssistantMessage("Das Formular liegt im Bürgerbüro aus."));

    List<String> subQueries =
        service.decompose(DecompositionContext.of("Wie lange dauert das?", searchWindow), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordDegenerateDecomposition();
  }

  /**
   * The anchor-space invariant: a context block rendered into the decomposition prompt widens the
   * anchor space by construction. The block here is the shape a Gesprächsnotiz point will have
   * ("Bezugsjahr 2024"); without it the same sub-query is judged unrelated and the whole run falls
   * back, which is exactly the failure the invariant prevents.
   */
  @Test
  void aRenderedContextBlockWidensTheAnchorSpace() {
    stubChatModelResponse("Gebührenordnung Bezugsjahr 2024");

    List<String> subQueries =
        service.decompose(
            DecompositionContext.of("Was kostet der Ausweis?", List.of())
                .withContextBlock("Bezugsjahr 2024"),
            3);

    assertThat(subQueries).containsExactly("Gebührenordnung Bezugsjahr 2024");
    assertThat(capturedSystemPrompt()).contains("Bezugsjahr 2024");
    verifyNoInteractions(metrics);
  }

  /**
   * The acceptance case of #1487, with the <b>real</b> rendered Gesprächsnotiz block rather than a
   * bare string: the note point "Bezugsjahr 2024" is the only anchor the sub-query
   * "Anwohnerparkausweis Gebühren 2024" shares with anything the decomposition was given. Without
   * the note in the anchor space the belt would judge it unrelated and - being all or nothing -
   * take the whole run into the fallback, precisely in the {@code constraint_carryover} case.
   */
  @Test
  void aSubQueryAnchoredOnlyInTheRenderedNoteSurvivesTheSafetyBelt() {
    stubChatModelResponse("Anwohnerparkausweis Gebühren 2024");
    String noteBlock = ConversationNoteBlock.render(List.of("Bezugsjahr 2024"));

    List<String> withNote =
        service.decompose(
            DecompositionContext.of("Wie hoch sind dafür die Kosten?", List.of())
                .withContextBlock(noteBlock),
            3);

    assertThat(withNote).containsExactly("Anwohnerparkausweis Gebühren 2024");
    verifyNoInteractions(metrics);
  }

  /** The same sub-query without the note: unrelated, whole decomposition discarded. */
  @Test
  void withoutTheRenderedNoteTheSameSubQueryFallsBack() {
    stubChatModelResponse("Anwohnerparkausweis Gebühren 2024");

    List<String> subQueries =
        service.decompose(DecompositionContext.of("Wie hoch sind dafür die Kosten?", List.of()), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordDegenerateDecomposition();
  }

  /** The same sub-query without the block: unrelated, whole decomposition discarded. */
  @Test
  void withoutTheContextBlockTheSameSubQueryFallsBack() {
    stubChatModelResponse("Gebührenordnung Bezugsjahr 2024");

    List<String> subQueries =
        service.decompose(DecompositionContext.of("Was kostet der Ausweis?", List.of()), 3);

    assertThat(subQueries).isEmpty();
    verify(metrics).recordDegenerateDecomposition();
  }

  /**
   * The anchor-space invariant at the seam this class owns: the instruction this service passes to
   * {@link DecompositionContext#systemText} must be a <b>fixed</b> text, never one that carries
   * context of its own. Rendering a context building block into the instruction argument instead of
   * through {@link DecompositionContext#withContextBlock} would put it in front of the model while
   * leaving {@code countUnrelated} blind to it - and because the belt is all or nothing, every
   * sub-query that block legitimizes would take the whole run into the fallback.
   *
   * <p>Measured by running two entirely different contexts: once each prompt has its own {@link
   * DecompositionContext#contextTexts()} taken out, what remains must be identical. Anything the
   * prompt derives from the context outside the anchor space differs here.
   *
   * <p><b>Whoever adds an argument to {@code decompose} fills it differently in the two runs.</b>
   * The two runs are the whole mechanism: a new argument given the same value twice cancels out of
   * the comparison and leaves this test green while saying nothing.
   */
  @Test
  void nothingReachesTheModelOutsideTheInstructionAndTheAnchoredContext() {
    stubChatModelResponse("Irgendeine Zeile");
    DecompositionContext first =
        DecompositionContext.of(
                "Was kostet der Ausweis?",
                List.of(new UserMessage("Vorrunde zum Anwohnerparkausweis")))
            .withContextBlock(ConversationNoteBlock.render(List.of("Bezugsjahr 2024")));
    DecompositionContext second =
        DecompositionContext.of(
                "Welche Frist gilt?", List.of(new UserMessage("Vorrunde zum Widerspruch")))
            .withContextBlock(
                ConversationNoteBlock.render(List.of("Zuständig ist das Ordnungsamt")));

    service.decompose(first, 3);
    service.decompose(second, 3);

    ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel, times(2)).call(prompts.capture());
    assertThat(withoutAnchoredContext(prompts.getAllValues().get(0), first))
        .isEqualTo(withoutAnchoredContext(prompts.getAllValues().get(1), second));
    // The blocks do reach the model, and only their own run's does.
    assertThat(systemTextOf(prompts.getAllValues().get(0)))
        .contains("Bezugsjahr 2024")
        .doesNotContain("Ordnungsamt");
  }

  /**
   * The whole rendered prompt with every anchored text removed - the instruction, and nothing else
   * if the invariant holds.
   */
  private static String withoutAnchoredContext(Prompt prompt, DecompositionContext context) {
    String rendered =
        prompt.getInstructions().stream().map(Message::getText).collect(Collectors.joining("\n"));
    for (String contextText : context.contextTexts()) {
      rendered = rendered.replace(contextText, "");
    }
    return rendered.strip();
  }

  private static String systemTextOf(Prompt prompt) {
    return prompt.getInstructions().stream()
        .filter(message -> message.getMessageType() == MessageType.SYSTEM)
        .map(Message::getText)
        .findFirst()
        .orElseThrow();
  }
}
