package io.opaa.indexing.document;

import io.opaa.api.types.DocumentStatus;
import io.opaa.common.DatabaseConnectionLoss;
import io.opaa.indexing.job.IndexingEventSink;
import io.opaa.indexing.job.RunEndingFailures;
import io.opaa.instance.CurrentInstance;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Repeats an ingest whose write lost its database connection ({@link DatabaseConnectionLoss}),
 * after a growing pause and at most {@link WriteRetryProperties#maxAttempts} times, as long as the
 * first loss lies less than {@link WriteRetryProperties#window} back. Each attempt runs in
 * transactions of its own; a caller inside a transaction is never repeated, its transaction is
 * broken. Any other failure ends the ingest as before.
 *
 * <p>Before every repetition the ingest must still hold what it writes for (ADR-0053, Entscheidung
 * 8): its run through {@link IndexingEventSink#requireHeld}, which ends a run given up meanwhile
 * with a {@code RunAbandonedException}, and a row bound to a processing instance (an upload) stays
 * {@code PENDING} under this instance and its heartbeat, else {@link IngestNoLongerHeldException}.
 * A lost connection during that check counts as a failed attempt.
 */
public class ConnectionLossRetry {

  /** One attempt, nothing repeated. */
  public static final ConnectionLossRetry NONE =
      new ConnectionLossRetry(
          new WriteRetryProperties(1, null, null), null, null, null, Clock.systemUTC());

  static final long MAX_PAUSE_MILLIS = 10_000;

  private static final Logger log = LoggerFactory.getLogger(ConnectionLossRetry.class);

  /** One ingest attempt; {@code repeat} answers whether a failure of it will be repeated. */
  @FunctionalInterface
  public interface Attempt {
    DocumentIngestResult run(Repeat repeat) throws IOException;
  }

  /** A pause between two attempts. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration pause) throws InterruptedException;
  }

  private final WriteRetryProperties properties;
  private final DocumentRepository documentRepository;
  private final CurrentInstance currentInstance;
  private final IndexingMetrics metrics;
  private final Clock clock;
  private final Sleeper sleeper;

  public ConnectionLossRetry(
      WriteRetryProperties properties,
      DocumentRepository documentRepository,
      CurrentInstance currentInstance,
      IndexingMetrics metrics,
      Clock clock) {
    this(properties, documentRepository, currentInstance, metrics, clock, Thread::sleep);
  }

  ConnectionLossRetry(
      WriteRetryProperties properties,
      DocumentRepository documentRepository,
      CurrentInstance currentInstance,
      IndexingMetrics metrics,
      Clock clock,
      Sleeper sleeper) {
    this.properties = properties;
    this.documentRepository = documentRepository;
    this.currentInstance = currentInstance;
    this.metrics = metrics;
    this.clock = clock;
    this.sleeper = sleeper;
  }

  /**
   * Runs {@code attempt} for {@code ingest} until it returns, fails for good or no longer holds
   * what it writes for; {@code events} is the protocol of its caller, {@code null} for none.
   */
  public DocumentIngestResult run(DocumentIngest ingest, IndexingEventSink events, Attempt attempt)
      throws IOException {
    ExponentialBackOff backOff = new ExponentialBackOff(properties.initialDelay().toMillis(), 2.0);
    backOff.setMaxInterval(MAX_PAUSE_MILLIS);
    BackOffExecution pauses = backOff.start();
    Instant firstLoss = null;
    for (int number = 1; ; number++) {
      Repeat repeat = new Repeat(number, firstLoss);
      try {
        if (number > 1) {
          requireHeldBeforeRepeat(ingest, events);
        }
        return attempt.run(repeat);
      } catch (IOException | RuntimeException e) {
        if (!repeat.after(e)) {
          if (repeat.lostConnection) {
            record("exhausted");
          }
          throw e;
        }
        firstLoss = repeat.firstLoss;
        long pause = pauses.nextBackOff();
        log.warn(
            "Lost the database connection while storing {} (attempt {} of {}); repeating in {} ms",
            ingest.library().loggedNames().of(ingest.filePath()),
            number,
            properties.maxAttempts(),
            pause);
        record("repeated");
        pause(pause, e);
      }
    }
  }

  /**
   * The check before a repetition; a lost connection in it is one more failed attempt, judged by
   * the same bounds, so the ingest waits here until the database answers again.
   */
  private void requireHeldBeforeRepeat(DocumentIngest ingest, IndexingEventSink events) {
    try {
      if (events != null) {
        events.requireHeld();
      }
      requireRowHeld(ingest);
    } catch (RuntimeException e) {
      if (!DatabaseConnectionLoss.isConnectionLoss(e)) {
        record("not_held");
      }
      throw e;
    }
  }

  private void requireRowHeld(DocumentIngest ingest) {
    if (currentInstance == null) {
      return;
    }
    documentRepository
        .findByLibraryIdAndFilePath(ingest.library().getId(), ingest.filePath())
        .filter(row -> row.getProcessingInstanceId() != null)
        .ifPresent(this::requireHeld);
  }

  private void requireHeld(Document row) {
    if (row.getStatus() != DocumentStatus.PENDING) {
      throw new IngestNoLongerHeldException(row.getId(), "it is " + row.getStatus());
    }
    if (!row.getProcessingInstanceId().equals(currentInstance.id())) {
      throw new IngestNoLongerHeldException(row.getId(), "another instance processes it");
    }
    if (!currentInstance.heartbeatCurrent()) {
      throw new IngestNoLongerHeldException(row.getId(), "the heartbeat of this instance lapsed");
    }
  }

  private void pause(long millis, Exception failure) {
    try {
      sleeper.sleep(Duration.ofMillis(millis));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      IllegalStateException stopped =
          new IllegalStateException("interrupted before repeating a write", interrupted);
      stopped.addSuppressed(failure);
      throw stopped;
    }
  }

  private void record(String result) {
    if (metrics != null) {
      metrics.recordWriteRepeat(result);
    }
  }

  /**
   * Whether a failure of one attempt is repeated, decided once: the attempt asks before it books a
   * failure, so a repeated attempt books nothing and leaves the row to the next one.
   */
  public final class Repeat {

    private final int number;
    private Instant firstLoss;
    private Boolean decision;
    private boolean lostConnection;

    private Repeat(int number, Instant firstLoss) {
      this.number = number;
      this.firstLoss = firstLoss;
    }

    /**
     * Whether this attempt is followed by another after {@code failure}; asked twice, same answer.
     */
    public boolean after(Throwable failure) {
      if (decision == null) {
        decision = decide(failure);
      }
      return decision;
    }

    private boolean decide(Throwable failure) {
      lostConnection =
          DatabaseConnectionLoss.isConnectionLoss(failure)
              && RunEndingFailures.endingCause(failure) == null;
      if (!lostConnection
          || Thread.currentThread().isInterrupted()
          || TransactionSynchronizationManager.isActualTransactionActive()) {
        return false;
      }
      Instant now = Instant.now(clock);
      if (firstLoss == null) {
        firstLoss = now;
      }
      return number < properties.maxAttempts()
          && Duration.between(firstLoss, now).compareTo(properties.window()) < 0;
    }
  }
}
