package io.opaa.query.spike;

import io.opaa.chat.Chat;
import io.opaa.chat.ChatNoteExtractionService;
import io.opaa.chat.ChatNotePoint;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatSource;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.observability.QueryMetrics;
import io.opaa.query.QueryOutcome;
import io.opaa.query.QueryResult;
import io.opaa.query.answer.ChatResponses;
import io.opaa.query.answer.ConversationWindowMessages;
import io.opaa.query.citation.ChatSourceAssembler;
import io.opaa.query.citation.CitationParser;
import io.opaa.query.citation.CitationValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Handles a chat turn whose question begins with {@code "@test "} while {@code
 * opaa.spike.tool-loop.enabled} is set (#1789) - a throwaway path ahead of epic #1747 that bypasses
 * {@code io.opaa.query.retrieval.RetrievalPipeline} and {@code AnswerGenerationService} entirely in
 * favour of a model-driven tool-calling loop over the one {@link SearchKnowledgeTool}. {@code
 * io.opaa.query.QueryService#query} calls {@link #handle} after resolving the chat, the space
 * guards, the readable libraries and the search scope, so rights and scope are exactly those of an
 * ordinary turn; every other question reaches this class only to be handed straight back via an
 * empty {@link Optional}, leaving the ordinary path to run unmodified.
 *
 * <p>{@code ActiveChatModelResolver#resolveChatClient()} already auto-registers a default {@code
 * ToolCallingAdvisor} for any call carrying tools ({@code DefaultChatClient}, "Auto-registers a
 * ToolCallingAdvisor unless ... a ToolAdvisor is already present"), so nothing about the resolver
 * needs to change for the loop itself to run. This class still supplies its own {@link
 * ToolCallingAdvisor} per call, built from a {@link ToolCallingManager} capped at {@link
 * #MAX_TOOL_CALLS} - the auto-registered default has no configurable limit - which the presence
 * check in {@code DefaultChatClient} then leaves untouched instead of adding a second one.
 */
@Service
@ConditionalOnProperty(name = "opaa.spike.tool-loop.enabled", havingValue = "true")
public class SpikeToolLoopQueryHandler {

  /** Hard per-turn cap (#1789 acceptance criterion), enforced by {@link ToolCallingManager}. */
  static final int MAX_TOOL_CALLS = 4;

  private static final String TEST_PREFIX = "@test ";

  private static final String SYSTEM_PROMPT =
      """
      Du bist ein hilfsbereiter Projektassistent.

      Antworte immer auf Deutsch, unabhängig von der Sprache der Frage, des Gesprächsverlaufs \
      und der Kontextdokumente.

      ZITIERREGELN (verbindlich):
      - Belege jede Quelle, die du verwendest, mit einer Zitiermarke direkt im Antworttext.
      - Verwende genau dieses Format: 【source: <document_id>#<chunk_index> | <file_name>】
      - Übernimm die Werte exakt aus dem [Quelle]-Kopf des jeweiligen Kontextabschnitts.
      - Erfinde keine Zitiermarken. Zitiere nur Dokumente, die ein Werkzeugaufruf geliefert hat.

      Suche vor jeder inhaltlichen Antwort mit dem Werkzeug search_knowledge nach den passenden \
      Textstellen; beantworte die Frage erst danach.\
      """;

  private final ActiveChatModelResolver activeChatModelResolver;
  private final SearchKnowledgeTool searchKnowledgeTool;
  private final ChatMemory chatMemory;
  private final CitationParser citationParser;
  private final CitationValidator citationValidator;
  private final ChatSourceAssembler chatSourceAssembler;
  private final ChatService chatService;
  private final ChatNoteExtractionService chatNoteExtractionService;
  private final QueryMetrics metrics;

  SpikeToolLoopQueryHandler(
      ActiveChatModelResolver activeChatModelResolver,
      SearchKnowledgeTool searchKnowledgeTool,
      ChatMemory chatMemory,
      CitationParser citationParser,
      CitationValidator citationValidator,
      ChatSourceAssembler chatSourceAssembler,
      ChatService chatService,
      ChatNoteExtractionService chatNoteExtractionService,
      QueryMetrics metrics) {
    this.activeChatModelResolver = activeChatModelResolver;
    this.searchKnowledgeTool = searchKnowledgeTool;
    this.chatMemory = chatMemory;
    this.citationParser = citationParser;
    this.citationValidator = citationValidator;
    this.chatSourceAssembler = chatSourceAssembler;
    this.chatService = chatService;
    this.chatNoteExtractionService = chatNoteExtractionService;
    this.metrics = metrics;
  }

  /**
   * Empty for any question without the {@code "@test "} prefix (case-insensitive) - the caller then
   * runs its own, unmodified path. Otherwise runs the tool-calling loop against {@code rawQuestion}
   * with the prefix stripped, validates the resulting citations exactly as the ordinary path does,
   * persists the turn via {@code chatService.appendTurn} and returns the finished {@link
   * QueryResult}.
   *
   * @param startTime {@code System.currentTimeMillis()} at the start of the caller's turn, for
   *     {@link QueryOutcome#durationMs()} - the same reference point the ordinary path uses.
   */
  public Optional<QueryResult> handle(
      String rawQuestion,
      Optional<Chat> chat,
      UUID effectiveChatId,
      String conversationKey,
      List<ChatNotePoint> notePoints,
      Set<UUID> searchScope,
      MetadataFilter metadataFilter,
      long startTime) {
    if (rawQuestion == null
        || !rawQuestion.regionMatches(true, 0, TEST_PREFIX, 0, TEST_PREFIX.length())) {
      return Optional.empty();
    }
    String question = rawQuestion.substring(TEST_PREFIX.length());

    ToolLoopRunState runState = new ToolLoopRunState(searchScope, metadataFilter);
    List<Message> messages = new ArrayList<>(chatMemory.get(conversationKey));
    messages.add(new UserMessage(question));

    ToolCallingManager toolCallingManager =
        ToolCallingManager.builder().maxTotalToolCalls(MAX_TOOL_CALLS).build();
    ToolCallingAdvisor toolCallingAdvisor =
        ToolCallingAdvisor.builder().toolCallingManager(toolCallingManager).build();

    ChatResponse response =
        activeChatModelResolver
            .resolveChatClient()
            .prompt()
            .system(SYSTEM_PROMPT)
            .messages(messages)
            .tools(searchKnowledgeTool)
            .toolContext(Map.of(SearchKnowledgeTool.RUN_STATE_KEY, runState))
            .advisors(toolCallingAdvisor)
            .call()
            .chatResponse();

    String rawAnswer = ChatResponses.text(response);
    List<Document> chunks = runState.collectedChunks();
    List<CitationValidator.ValidatedCitation> validatedCitations =
        citationValidator.validate(citationParser.extractCitations(rawAnswer), chunks, rawAnswer);
    List<ChatSource> sources =
        chatSourceAssembler.assemble(chunks, validatedCitations, metadataFilter);
    String answer = rawAnswer + searchStepsBlock(runState.searchSteps());

    // Kept in step with the ordinary path (AnswerGenerationService#generateAnswer): a follow-up
    // turn in the same chat must see this one in its conversation window regardless of which path
    // answered it.
    chatMemory.add(conversationKey, new UserMessage(question));
    ConversationWindowMessages.answer(rawAnswer)
        .ifPresent(message -> chatMemory.add(conversationKey, message));

    String chatTitle =
        chat.map(c -> chatService.appendTurn(c, rawQuestion, answer, sources)).orElse(null);
    chat.ifPresent(
        c -> chatNoteExtractionService.condenseAsync(c.getId(), c.getSpaceId(), question));

    long durationMs = System.currentTimeMillis() - startTime;
    int tokenCount = ChatResponses.totalTokens(response);
    metrics.recordSuccess(tokenCount);

    QueryOutcome metadata =
        new QueryOutcome(
            ChatResponses.model(response),
            tokenCount,
            durationMs,
            false,
            false,
            chatSourceAssembler.searchedLibraries(searchScope));
    return Optional.of(
        new QueryResult(answer, sources, metadata, effectiveChatId, chatTitle, notePoints));
  }

  /** The debug line replacing streaming (#1789): every teilfrage the model formulated, in order. */
  private static String searchStepsBlock(List<String> steps) {
    if (steps.isEmpty()) {
      return "";
    }
    StringBuilder block = new StringBuilder("\n\nSuchschritte:");
    for (String step : steps) {
      block.append("\n- ").append(step);
    }
    return block.toString();
  }
}
