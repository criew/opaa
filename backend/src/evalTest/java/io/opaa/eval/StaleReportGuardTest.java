package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.chat.ChatNoteExtractionService;
import io.opaa.chat.ChatNoteProperties;
import io.opaa.indexing.IndexingProperties;
import io.opaa.query.RetrievalContextFactory;
import io.opaa.query.answer.CaffeineChatMemoryRepository;
import io.opaa.query.retrieval.RetrievalPipeline;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

/**
 * Guards the one thing the baseline checks cannot see for themselves (#1671): that a run which
 * writes no report leaves none behind. Both guarded measurement paths swallow their own failures,
 * so without this the previous run's report survives and the check - which only asks whether the
 * file exists - compares it as this run's measurement.
 */
class StaleReportGuardTest {

  @Test
  void discardRemovesAnExistingReportAndToleratesAMissingOne(@TempDir Path directory)
      throws IOException {
    Path present = Files.writeString(directory.resolve("report.json"), "{}");
    Path absent = directory.resolve("never-written.json");

    StaleReportGuard.discard(present, absent);

    assertThat(present).doesNotExist();
    assertThat(absent).doesNotExist();
  }

  @Test
  void discardFailsRatherThanLeavingAReportTheCheckWouldReadAsFresh(@TempDir Path directory) {
    // A non-empty directory in place of the report file stands in for any report that cannot be
    // removed. Continuing silently would be exactly the failure this guard exists to prevent.
    Path undeletable = directory.resolve("report.json");

    assertThatThrownBy(
            () -> {
              Files.createDirectory(undeletable);
              Files.writeString(undeletable.resolve("occupant"), "x");
              StaleReportGuard.discard(undeletable);
            })
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("previous run's measurement");
  }

  /**
   * The ordering is the whole point: the discard happens before anything else, so an unmet
   * precondition or a failed measurement - both of which write nothing and are deliberately
   * swallowed - cannot leave the previous run's report for the baseline check.
   */
  @Test
  void aMultiTurnRunThatMeasuresNothingLeavesNoReportBehind(@TempDir Path reportDirectory)
      throws IOException {
    System.setProperty(EvalReportDirectory.PROPERTY, reportDirectory.toString());
    Path report = ConversationHarnessSupport.reportFile(EvalDomainConfig.VERWALTUNG);
    Files.writeString(report, "{\"conversationMeasurementContractVersion\": 4}");

    // A mocked factory whose queryProperties() returns null makes the guarded section fail at
    // once - the shape of every run that ends without writing a report.
    ConversationHarnessSupport.runAndWriteGuarded(
        EvalDomainConfig.VERWALTUNG,
        identity(),
        mock(RetrievalPipeline.class),
        mock(RetrievalContextFactory.class),
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new CaffeineChatMemoryRepository(new SimpleMeterRegistry()))
            .maxMessages(20)
            .build(),
        mock(ChatNoteExtractionService.class),
        new ChatNoteProperties(10),
        new IndexingProperties(1000, 200, 50, null, null, null, null, 0),
        UUID.randomUUID(),
        LoggerFactory.getLogger(StaleReportGuardTest.class));

    assertThat(report)
        .as("the previous run's report must not survive a run that measured nothing")
        .doesNotExist();
  }

  @AfterEach
  void clearReportDirectory() {
    System.clearProperty(EvalReportDirectory.PROPERTY);
  }

  private static PipelineHarnessSupport.RunIdentity identity() {
    return new PipelineHarnessSupport.RunIdentity(
        "ollama",
        "nomic-embed-text:v1.5",
        "digest",
        "ollama/ollama:0.6.5",
        "haswell",
        768,
        true,
        "hnsw",
        "corpus",
        72,
        "eval/golden/verwaltung.json",
        "hash",
        true,
        "markdown:3",
        "prefix",
        "qwen2.5:1.5b-instruct");
  }
}
