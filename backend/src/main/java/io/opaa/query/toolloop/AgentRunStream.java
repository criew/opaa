package io.opaa.query.toolloop;

import io.opaa.chat.AgentRunRecord;
import io.opaa.query.answer.ActiveAnswerStreams;
import io.opaa.query.answer.AnswerReaderGoneException;
import io.opaa.query.answer.AnswerStreamListener;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The stream of one agent run (ADR-0070, ADR-0053): it tells the reader the steps, keeps the
 * connection alive with a ping every {@code heartbeat} also while the model or a tool works, and
 * decides at each {@link #checkpoint} whether the run must end. A run ends when the person stopped
 * it - the mark lies in the database, set by any instance -, when a write to the reader failed, or
 * when the instance stops; the stop also interrupts the request thread, so a waiting model call
 * returns at once. A closed connection is noticed only at a later write, which is why the stop mark
 * and not the failed write is what keeps a tool from running.
 */
final class AgentRunStream implements AgentRunEvents, AutoCloseable {

  private final AnswerStreamListener reader;
  private final UUID runId;
  private final AgentWorkspace workspace;
  private final Thread requestThread = Thread.currentThread();
  private final ActiveAnswerStreams.Registration registration;
  private final ScheduledFuture<?> heartbeat;
  private volatile boolean readerGone;
  private volatile boolean shuttingDown;

  AgentRunStream(
      AnswerStreamListener reader,
      UUID runId,
      AgentWorkspace workspace,
      ActiveAnswerStreams streams,
      ScheduledExecutorService pinger,
      Duration interval) {
    this.reader = reader;
    this.runId = runId;
    this.workspace = workspace;
    this.registration = streams.register(this::shutDown);
    this.heartbeat =
        pinger.scheduleWithFixedDelay(
            this::ping, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void shutDown() {
    shuttingDown = true;
    requestThread.interrupt();
  }

  private void ping() {
    if (readerGone) {
      return;
    }
    try {
      reader.idle();
    } catch (IOException | RuntimeException e) {
      readerGone = true;
    }
  }

  /** Whether the instance stopped under the run; the run then ends with {@code SHUTTING_DOWN}. */
  boolean shuttingDown() {
    return shuttingDown;
  }

  @Override
  public void checkpoint() {
    if (shuttingDown) {
      throw ActiveAnswerStreams.shuttingDown();
    }
    if (readerGone) {
      throw new AnswerReaderGoneException(new IOException("the reader of the run went away"));
    }
    if (workspace.cancelRequested(runId)) {
      throw new AnswerReaderGoneException(new IOException("the person stopped the run"));
    }
  }

  @Override
  public void stepStarted(int index, String tool, String detail) {
    send(() -> reader.stepStarted(index, tool, detail));
  }

  @Override
  public void stepFinished(int index, AgentRunRecord.Step step) {
    send(() -> reader.stepFinished(index, step));
  }

  @Override
  public void limitReached(AgentRunRecord.EndState limit) {
    send(() -> reader.limitReached(limit));
  }

  private void send(Write write) {
    try {
      write.run();
    } catch (IOException e) {
      readerGone = true;
      throw new AnswerReaderGoneException(e);
    }
  }

  @FunctionalInterface
  private interface Write {
    void run() throws IOException;
  }

  /** Stops the pings and the shutdown hook; clears an interrupt the stop left on the thread. */
  @Override
  public void close() {
    heartbeat.cancel(false);
    registration.close();
    if (shuttingDown) {
      Thread.interrupted();
    }
  }
}
