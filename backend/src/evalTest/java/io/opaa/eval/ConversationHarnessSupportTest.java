package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Docker-free guard for the one thing this whole path stands or falls on (issue #1484): that the
 * turn's conversation window actually reaches the production {@link RetrievalContext}. An {@code
 * List.of()} in its place would leave every other assertion of this suite green while the run
 * reported multi-turn numbers that measured standalone questions.
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
