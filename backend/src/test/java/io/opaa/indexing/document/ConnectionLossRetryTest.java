package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.DocumentStatus;
import io.opaa.indexing.job.IndexingEventSink;
import io.opaa.indexing.job.RunAbandonedException;
import io.opaa.instance.CurrentInstance;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.observability.IndexingMetrics;
import io.opaa.test.MutableClock;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ConnectionLossRetryTest {

  private final UUID instanceId = UUID.randomUUID();
  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-28T10:00:00Z"));
  private final List<Duration> pauses = new ArrayList<>();
  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final CurrentInstance currentInstance = mock(CurrentInstance.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(UUID.randomUUID(), "Bibliothek", null, UUID.randomUUID());
  private final DocumentIngest ingest =
      DocumentIngest.text(library, "vermerk.txt", "Ein Vermerk.")
          .sourceType(SourceType.UPLOAD)
          .build();

  private ConnectionLossRetry retry;

  @BeforeEach
  void setUp() {
    lenient().when(currentInstance.id()).thenReturn(instanceId);
    lenient().when(currentInstance.heartbeatCurrent()).thenReturn(true);
    lenient()
        .when(documentRepository.findByLibraryIdAndFilePath(any(), any()))
        .thenReturn(Optional.empty());
    retry = retry(new WriteRetryProperties(5, Duration.ofSeconds(60), Duration.ofSeconds(1)));
  }

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private ConnectionLossRetry retry(WriteRetryProperties properties) {
    return new ConnectionLossRetry(
        properties,
        documentRepository,
        currentInstance,
        new IndexingMetrics(meterRegistry),
        clock,
        pause -> {
          pauses.add(pause);
          clock.advance(pause);
        });
  }

  private static DataAccessResourceFailureException lost() {
    return new DataAccessResourceFailureException(
        "write", new SQLException("An I/O error occurred while sending to the backend.", "08006"));
  }

  private double repeats(String result) {
    var counter = meterRegistry.find("opaa.indexing.write.repeats").tag("result", result).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void aWriteThatLostItsConnectionIsRepeatedAfterAGrowingPause() throws IOException {
    AtomicInteger attempts = new AtomicInteger();
    List<Boolean> asked = new ArrayList<>();

    DocumentIngestResult result =
        retry.run(
            ingest,
            null,
            repeat -> {
              if (attempts.incrementAndGet() < 3) {
                RuntimeException failure = lost();
                asked.add(repeat.after(failure));
                throw failure;
              }
              return DocumentIngestResult.PROCESSED;
            });

    assertThat(result).isEqualTo(DocumentIngestResult.PROCESSED);
    assertThat(attempts).hasValue(3);
    assertThat(asked).containsExactly(true, true);
    assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    assertThat(repeats("repeated")).isEqualTo(2);
  }

  @Test
  void aPermanentFailureIsNotRepeated() {
    AtomicInteger attempts = new AtomicInteger();
    DataIntegrityViolationException constraint =
        new DataIntegrityViolationException("check", new SQLException("check", "23514"));

    assertThatThrownBy(
            () ->
                retry.run(
                    ingest,
                    null,
                    repeat -> {
                      attempts.incrementAndGet();
                      assertThat(repeat.after(constraint)).isFalse();
                      throw constraint;
                    }))
        .isSameAs(constraint);
    assertThat(attempts).hasValue(1);
    assertThat(pauses).isEmpty();
  }

  @Test
  void theNumberOfAttemptsIsBounded() {
    AtomicInteger attempts = new AtomicInteger();
    ConnectionLossRetry bounded =
        retry(new WriteRetryProperties(3, Duration.ofMinutes(10), Duration.ofSeconds(1)));

    assertThatThrownBy(
            () ->
                bounded.run(
                    ingest,
                    null,
                    repeat -> {
                      attempts.incrementAndGet();
                      throw lost();
                    }))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(attempts).hasValue(3);
    assertThat(repeats("exhausted")).isEqualTo(1);
  }

  @Test
  void noAttemptStartsOnceTheWindowSinceTheFirstLossHasPassed() {
    AtomicInteger attempts = new AtomicInteger();
    ConnectionLossRetry windowed =
        retry(new WriteRetryProperties(100, Duration.ofSeconds(30), Duration.ofSeconds(1)));

    assertThatThrownBy(
            () ->
                windowed.run(
                    ingest,
                    null,
                    repeat -> {
                      attempts.incrementAndGet();
                      // every attempt waits ten seconds in the pool before it fails
                      clock.advance(Duration.ofSeconds(10));
                      throw lost();
                    }))
        .isInstanceOf(DataAccessResourceFailureException.class);
    // losses 10, 21, 33 and 47 seconds after the start: the fourth lies 37 seconds after the first
    assertThat(attempts).hasValue(4);
  }

  @Test
  void aRunGivenUpMeanwhileIsNotContinued() {
    AtomicInteger attempts = new AtomicInteger();
    RunAbandonedException abandoned =
        new RunAbandonedException(UUID.randomUUID(), "its job is no longer RUNNING");
    IndexingEventSink events =
        new IndexingEventSink() {
          @Override
          public void record(
              io.opaa.indexing.job.IndexingEventCategory category,
              String message,
              String reference) {}

          @Override
          public void requireHeld() {
            throw abandoned;
          }
        };

    assertThatThrownBy(
            () ->
                retry.run(
                    ingest,
                    events,
                    repeat -> {
                      attempts.incrementAndGet();
                      throw lost();
                    }))
        .isSameAs(abandoned);
    assertThat(attempts).hasValue(1);
    assertThat(repeats("not_held")).isEqualTo(1);
  }

  @Test
  void aLostConnectionWhileCheckingTheHoldIsOneMoreAttempt() throws IOException {
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger checks = new AtomicInteger();
    IndexingEventSink events =
        new IndexingEventSink() {
          @Override
          public void record(
              io.opaa.indexing.job.IndexingEventCategory category,
              String message,
              String reference) {}

          @Override
          public void requireHeld() {
            if (checks.incrementAndGet() == 1) {
              throw lost();
            }
          }
        };

    DocumentIngestResult result =
        retry.run(
            ingest,
            events,
            repeat -> {
              if (attempts.incrementAndGet() == 1) {
                throw lost();
              }
              return DocumentIngestResult.PROCESSED;
            });

    assertThat(result).isEqualTo(DocumentIngestResult.PROCESSED);
    assertThat(attempts).hasValue(2);
    assertThat(checks).hasValue(2);
    assertThat(pauses).hasSize(2);
  }

  @Test
  void anUploadIsOnlyRepeatedWhileItsRowIsPendingUnderThisInstance() throws IOException {
    Document row = new Document("vermerk.txt", "vermerk.txt", "text/plain", 1L, SourceType.UPLOAD);
    row.setProcessingInstanceId(instanceId);
    when(documentRepository.findByLibraryIdAndFilePath(any(), any())).thenReturn(Optional.of(row));

    assertThat(failingOnce()).isEqualTo(DocumentIngestResult.PROCESSED);

    row.setStatus(DocumentStatus.FAILED);
    assertThatThrownBy(this::failingOnce)
        .isInstanceOf(IngestNoLongerHeldException.class)
        .hasMessageContaining("FAILED");

    row.setStatus(DocumentStatus.PENDING);
    row.setProcessingInstanceId(UUID.randomUUID());
    assertThatThrownBy(this::failingOnce)
        .isInstanceOf(IngestNoLongerHeldException.class)
        .hasMessageContaining("another instance");

    row.setProcessingInstanceId(instanceId);
    when(currentInstance.heartbeatCurrent()).thenReturn(false);
    assertThatThrownBy(this::failingOnce)
        .isInstanceOf(IngestNoLongerHeldException.class)
        .hasMessageContaining("heartbeat");
  }

  @Test
  void aRowWithoutProcessingInstanceIsRepeatedWithoutAHoldOfItsOwn() throws IOException {
    Document row = new Document("vermerk.txt", "vermerk.txt", "text/plain", 1L);
    row.setStatus(DocumentStatus.FAILED);
    when(documentRepository.findByLibraryIdAndFilePath(any(), any())).thenReturn(Optional.of(row));

    assertThat(failingOnce()).isEqualTo(DocumentIngestResult.PROCESSED);
  }

  @Test
  void aCallerInsideATransactionIsNotRepeated() {
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(this::failingOnce).isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(pauses).isEmpty();
  }

  @Test
  void theDecisionOfTheAttemptStandsForTheLoop() {
    AtomicInteger attempts = new AtomicInteger();
    DataIntegrityViolationException constraint =
        new DataIntegrityViolationException("check", new SQLException("check", "23514"));

    // the attempt booked a permanent failure, then its booking lost the connection
    assertThatThrownBy(
            () ->
                retry.run(
                    ingest,
                    null,
                    repeat -> {
                      attempts.incrementAndGet();
                      repeat.after(constraint);
                      throw lost();
                    }))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(attempts).hasValue(1);
  }

  @Test
  void anInterruptedPauseEndsTheIngestWithTheInterruptKept() {
    ConnectionLossRetry interrupted =
        new ConnectionLossRetry(
            new WriteRetryProperties(5, Duration.ofSeconds(60), Duration.ofSeconds(1)),
            documentRepository,
            currentInstance,
            null,
            clock,
            pause -> {
              throw new InterruptedException("stop");
            });
    try {
      assertThatThrownBy(
              () ->
                  interrupted.run(
                      ingest,
                      null,
                      repeat -> {
                        throw lost();
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasCauseInstanceOf(InterruptedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void noneRepeatsNothing() {
    AtomicInteger attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                ConnectionLossRetry.NONE.run(
                    ingest,
                    null,
                    repeat -> {
                      attempts.incrementAndGet();
                      throw lost();
                    }))
        .isInstanceOf(DataAccessResourceFailureException.class);
    assertThat(attempts).hasValue(1);
  }

  private DocumentIngestResult failingOnce() throws IOException {
    AtomicInteger attempts = new AtomicInteger();
    return retry.run(
        ingest,
        null,
        repeat -> {
          if (attempts.incrementAndGet() == 1) {
            throw lost();
          }
          return DocumentIngestResult.PROCESSED;
        });
  }
}
