package io.opaa.query.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.chat.ChatNoteExtractionService;
import io.opaa.chat.ChatService;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.observability.QueryMetrics;
import io.opaa.query.KnowledgeRetrieval;
import io.opaa.query.QueryResult;
import io.opaa.query.citation.ChatSourceAssembler;
import io.opaa.query.citation.CitationParser;
import io.opaa.query.citation.CitationValidator;
import io.opaa.query.retrieval.RetrievalExplanation;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * #1789 acceptance criteria (c) and (d): the tool-calling loop actually calls {@link
 * SearchKnowledgeTool}, and the hard cap of {@link SpikeToolLoopQueryHandler#MAX_TOOL_CALLS} stops
 * it rather than looping forever against a model that keeps requesting the tool. Uses a raw {@link
 * ChatModel} mock wrapped in a real {@link ChatClient} - {@code ActiveChatModelResolver} is mocked
 * only to hand back that client, never the tool-calling loop itself, which is Spring AI's own and
 * out of this project's control (the very question the spike answers).
 */
class SpikeToolLoopQueryHandlerTest {

  private final KnowledgeRetrieval knowledgeRetrieval = mock(KnowledgeRetrieval.class);
  private final SearchKnowledgeTool searchKnowledgeTool =
      new SearchKnowledgeTool(knowledgeRetrieval);
  private final ActiveChatModelResolver activeChatModelResolver =
      mock(ActiveChatModelResolver.class);
  private final ChatMemory chatMemory = mock(ChatMemory.class);
  private final ChatSourceAssembler chatSourceAssembler = mock(ChatSourceAssembler.class);
  private final ChatService chatService = mock(ChatService.class);
  private final ChatNoteExtractionService chatNoteExtractionService =
      mock(ChatNoteExtractionService.class);
  private final ChatModel chatModel = mock(ChatModel.class);

  private SpikeToolLoopQueryHandler handler;

  @BeforeEach
  void setUp() {
    when(chatModel.getOptions()).thenReturn(ToolCallingChatOptions.builder().build());
    ChatClient chatClient =
        ChatClient.builder(
                chatModel, io.micrometer.observation.ObservationRegistry.NOOP, null, null)
            .build();
    when(activeChatModelResolver.resolveChatClient()).thenReturn(chatClient);
    when(chatMemory.get(any())).thenReturn(List.of());
    handler =
        new SpikeToolLoopQueryHandler(
            activeChatModelResolver,
            searchKnowledgeTool,
            chatMemory,
            new CitationParser(),
            new CitationValidator(),
            chatSourceAssembler,
            chatService,
            chatNoteExtractionService,
            new QueryMetrics(new SimpleMeterRegistry()));
  }

  private static ChatResponse toolCallResponse(String toolCallId, String frageJson) {
    AssistantMessage message =
        AssistantMessage.builder()
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        toolCallId, "function", "searchKnowledge", frageJson)))
            .build();
    return new ChatResponse(List.of(new Generation(message)));
  }

  /** Any question without the {@code "@test "} prefix never reaches the model at all. */
  @Test
  void handleReturnsEmptyAndNeverCallsTheModelForAQuestionWithoutThePrefix() {
    Optional<QueryResult> result =
        handler.handle(
            "Wie ist der Prozess?",
            Optional.empty(),
            UUID.randomUUID(),
            "conv-key",
            null,
            Set.of(),
            MetadataFilter.NONE,
            System.currentTimeMillis());

    assertThat(result).isEmpty();
    verifyNoInteractions(activeChatModelResolver, chatSourceAssembler);
  }

  /**
   * (c): one {@code search_knowledge} call, scoped to the search scope and filter {@code handle}
   * was given, then a final answer citing the chunk it returned.
   */
  @Test
  void handleRunsOneToolCallThenReturnsTheValidatedAnswerWithSearchSteps() {
    Set<UUID> searchScope = Set.of(UUID.randomUUID());
    MetadataFilter metadataFilter = MetadataFilter.NONE;
    Document chunk =
        Document.builder()
            .text("Anmeldungen laufen über das Bürgerportal.")
            .metadata(Map.of("file_name", "handbuch.md", "document_id", "doc-1", "chunk_index", 0))
            .build();
    when(knowledgeRetrieval.retrieve(
            eq("Wie melde ich mich an?"),
            eq(List.of()),
            eq(List.of()),
            eq(searchScope),
            eq(metadataFilter)))
        .thenReturn(
            new RetrievalPipelineResult(
                List.of(chunk), List.of(), new RetrievalExplanation(List.of()), true));
    when(chatSourceAssembler.assemble(eq(List.of(chunk)), any(), eq(metadataFilter)))
        .thenReturn(List.of());
    when(chatSourceAssembler.searchedLibraries(searchScope)).thenReturn(List.of());
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            toolCallResponse("call-1", "{\"frage\":\"Wie melde ich mich an?\"}"),
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "Über das Bürgerportal 【source: doc-1#0 | handbuch.md】")))));

    Optional<QueryResult> result =
        handler.handle(
            "@test Wie melde ich mich an?",
            Optional.empty(),
            UUID.randomUUID(),
            "conv-key",
            null,
            searchScope,
            metadataFilter,
            System.currentTimeMillis());

    assertThat(result).isPresent();
    assertThat(result.get().answer())
        .contains("Über das Bürgerportal")
        .contains("【source: doc-1#0 | handbuch.md】")
        .contains("Suchschritte:")
        .contains("Wie melde ich mich an?");
    verify(knowledgeRetrieval)
        .retrieve(
            eq("Wie melde ich mich an?"),
            eq(List.of()),
            eq(List.of()),
            eq(searchScope),
            eq(metadataFilter));
    verify(chatService, never()).appendTurn(any(), any(), any(), any());
  }

  /**
   * (d): a model that always requests the tool never runs more than {@link
   * SpikeToolLoopQueryHandler#MAX_TOOL_CALLS} real tool executions - Spring AI's own {@code
   * ToolCallingManager} limit ends the loop with a breach answer instead of calling forever.
   */
  @Test
  void handleStopsAtTheHardToolCallLimitInsteadOfLoopingForever() {
    when(knowledgeRetrieval.retrieve(any(), any(), any(), any(), any()))
        .thenReturn(
            new RetrievalPipelineResult(
                List.of(), List.of(), new RetrievalExplanation(List.of()), true));
    when(chatSourceAssembler.assemble(any(), any(), any())).thenReturn(List.of());
    when(chatSourceAssembler.searchedLibraries(any())).thenReturn(List.of());
    // The same tool-call response every round: a model that never stops asking for the tool.
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(toolCallResponse("call-n", "{\"frage\":\"Suche\"}"));

    Optional<QueryResult> result =
        handler.handle(
            "@test Suche",
            Optional.empty(),
            UUID.randomUUID(),
            "conv-key",
            null,
            Set.of(),
            MetadataFilter.NONE,
            System.currentTimeMillis());

    assertThat(result).isPresent();
    verify(knowledgeRetrieval, times(SpikeToolLoopQueryHandler.MAX_TOOL_CALLS))
        .retrieve(any(), any(), any(), any(), any());
  }
}
