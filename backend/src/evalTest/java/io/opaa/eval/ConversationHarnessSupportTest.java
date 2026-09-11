package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.ChatNoteItemKind;
import io.opaa.chat.ChatNoteCandidate;
import io.opaa.indexing.IndexingProperties;
import io.opaa.llm.RerankModelRole;
import io.opaa.llm.RerankRoleState;
import io.opaa.llm.RerankRoleStatus;
import io.opaa.query.QueryProperties;
import io.opaa.query.RetrievalContextFactory;
import io.opaa.query.answer.CaffeineChatMemoryRepository;
import io.opaa.query.retrieval.RetrievalContext;
import io.opaa.query.retrieval.RetrievalPipeline;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;

/**
 * Docker-free guard for the two things this whole path stands or falls on: that the turn's
 * conversation window (issue #1484) and its Gesprächsnotiz (#1487) actually reach the production
 * {@link RetrievalContext}. An {@code List.of()} in either place would leave every other assertion
 * of this suite green while the run reported multi-turn numbers measured without them.
 */
class ConversationHarnessSupportTest {

  @Test
  void everyTurnAfterTheFirstReachesThePipelineWithItsConversationWindow() throws IOException {
    List<RetrievalContext> contexts = new ArrayList<>();
    RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
    when(pipeline.run(any()))
        .thenAnswer(
            invocation -> {
              contexts.add(invocation.getArgument(0));
              return new RetrievalPipelineResult(List.of(), List.of("Teilfrage"), null);
            });
    RerankModelRole rerankModelRole = mock(RerankModelRole.class);
    when(rerankModelRole.currentStatus())
        .thenReturn(new RerankRoleStatus(RerankRoleState.DISABLED, null, null, null, false));
    RetrievalContextFactory contextFactory =
        new RetrievalContextFactory(
            new QueryProperties(8, 25, 1.0, 0.3, true, 3, 2, true, 0, 20, 2), rerankModelRole);

    ConversationHarnessSupport.measure(
        EvalDomainConfig.VERWALTUNG,
        identity(),
        pipeline,
        contextFactory,
        chatMemory(),
        ConversationRetrievalEvaluator.NoteExtraction.NONE,
        new ConversationMemoryProfile(20, 0, 0),
        new IndexingProperties(1000, 200, 50, null, null, null, null, 0),
        UUID.randomUUID(),
        List.of(twoTurnCase()),
        Instant.now());

    assertThat(contexts).hasSize(2);
    assertThat(contexts.get(0).conversationHistory())
        .as("the first turn of a case has no conversation yet")
        .isEmpty();
    assertThat(contexts.get(1).conversationHistory())
        .as("the second turn is retrieved for with the window the first turn produced")
        .extracting(Message::getText)
        .containsExactly("Was kostet ein Anwohnerparkausweis?", "30,70 Euro pro Jahr.");
    assertThat(contexts.get(1).question()).isEqualTo("Und bei Bedürftigkeit?");
  }

  /**
   * The note's counterpart of the assertion above (#1487): the points the production condensation
   * produced from a finished turn reach the next turn's {@link RetrievalContext} - and only the
   * {@code RAHMEN} ones, since the decomposition never sees an ANTWORTFORM point.
   */
  @Test
  void everyTurnAfterTheFirstReachesThePipelineWithTheRahmenPointsOfItsNote() throws IOException {
    List<RetrievalContext> contexts = new ArrayList<>();
    RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
    when(pipeline.run(any()))
        .thenAnswer(
            invocation -> {
              contexts.add(invocation.getArgument(0));
              return new RetrievalPipelineResult(List.of(), List.of("Teilfrage"), null);
            });
    RerankModelRole rerankModelRole = mock(RerankModelRole.class);
    when(rerankModelRole.currentStatus())
        .thenReturn(new RerankRoleStatus(RerankRoleState.DISABLED, null, null, null, false));
    RetrievalContextFactory contextFactory =
        new RetrievalContextFactory(
            new QueryProperties(8, 25, 1.0, 0.3, true, 3, 2, true, 0, 20, 2), rerankModelRole);

    ConversationHarnessSupport.measure(
        EvalDomainConfig.VERWALTUNG,
        identity(),
        pipeline,
        contextFactory,
        chatMemory(),
        userMessage ->
            List.of(
                new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN),
                new ChatNoteCandidate("Möchte knappe Antworten", ChatNoteItemKind.ANTWORTFORM)),
        new ConversationMemoryProfile(20, 2, 10),
        new IndexingProperties(1000, 200, 50, null, null, null, null, 0),
        UUID.randomUUID(),
        List.of(twoTurnCase()),
        Instant.now());

    assertThat(contexts).hasSize(2);
    assertThat(contexts.get(0).conversationNote())
        .as("nothing has been condensed before the first turn of a case")
        .isEmpty();
    assertThat(contexts.get(1).conversationNote())
        .as("the RAHMEN point of turn 1 reaches turn 2; the ANTWORTFORM point never does")
        .containsExactly("Bezugsjahr 2024");
  }

  /** Without a cap there is no note - the shape of a run measured before #1487. */
  @Test
  void aRunWithoutANoteCapReachesThePipelineWithNoNoteAtAll() throws IOException {
    List<RetrievalContext> contexts = new ArrayList<>();
    RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
    when(pipeline.run(any()))
        .thenAnswer(
            invocation -> {
              contexts.add(invocation.getArgument(0));
              return new RetrievalPipelineResult(List.of(), List.of("Teilfrage"), null);
            });
    RerankModelRole rerankModelRole = mock(RerankModelRole.class);
    when(rerankModelRole.currentStatus())
        .thenReturn(new RerankRoleStatus(RerankRoleState.DISABLED, null, null, null, false));
    RetrievalContextFactory contextFactory =
        new RetrievalContextFactory(
            new QueryProperties(8, 25, 1.0, 0.3, true, 3, 2, true, 0, 20, 2), rerankModelRole);

    ConversationHarnessSupport.measure(
        EvalDomainConfig.VERWALTUNG,
        identity(),
        pipeline,
        contextFactory,
        chatMemory(),
        userMessage -> List.of(new ChatNoteCandidate("Bezugsjahr 2024", ChatNoteItemKind.RAHMEN)),
        new ConversationMemoryProfile(20, 2, ConversationMemoryProfile.NO_CONVERSATION_NOTE),
        new IndexingProperties(1000, 200, 50, null, null, null, null, 0),
        UUID.randomUUID(),
        List.of(twoTurnCase()),
        Instant.now());

    assertThat(contexts).hasSize(2);
    assertThat(contexts).allSatisfy(context -> assertThat(context.conversationNote()).isEmpty());
  }

  private static ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder()
        .chatMemoryRepository(new CaffeineChatMemoryRepository(new SimpleMeterRegistry()))
        .maxMessages(20)
        .build();
  }

  private static ConversationCase twoTurnCase() {
    return new ConversationCase(
        "verw-conv-001",
        "verwaltung",
        "anaphora_resolution",
        List.of(
            new ConversationCase.Turn(
                "Was kostet ein Anwohnerparkausweis?",
                "30,70 Euro pro Jahr.",
                List.of("verwaltung-0001_a.md"),
                null),
            new ConversationCase.Turn(
                "Und bei Bedürftigkeit?",
                "Dann entfällt die Gebühr.",
                List.of("verwaltung-0002_b.md"),
                null)),
        null,
        GoldenCase.ExpectedState.KNOWN_GAP,
        "2026-09-11",
        "Grund",
        null);
  }

  private static PipelineHarnessSupport.RunIdentity identity() {
    return new PipelineHarnessSupport.RunIdentity(
        "ollama",
        "nomic-embed-text:v1.5",
        "digest",
        "ollama/ollama:0.6.5",
        768,
        true,
        "hnsw",
        "corpus",
        72,
        "eval/golden/verwaltung.json",
        "hash",
        true,
        "markdown:3",
        "qwen2.5:1.5b-instruct");
  }
}
