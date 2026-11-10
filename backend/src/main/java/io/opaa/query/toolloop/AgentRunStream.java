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
import java.util.function.Supplier;

/**
 * The stream of one agent run (ADR-0070, ADR-0053): it tells the reader the steps, keeps the
 * connection alive with a ping every {@code heartbeat} also while the model or a tool works, and
 * decides at each {@link #checkpoint} whether the run must end. A run ends when the person stopped
 * it - the mark lies in the database, set by any instance -, when a write to the reader failed, or
 * when the instance stops. A closed connection is noticed only at a later write, which is why the
 * stop mark and not the failed write is what keeps a tool from running.
 *
 * <p>The stop of the instance interrupts the request thread only while it waits for the model, so
 * that the call returns at once; after the {@link #lastCheckpoint} the run is kept and the stop is
 * ignored, and after {@link #close} the thread - a pooled web server worker - is never interrupted.
 */
final class AgentRunStream implements AgentRunEvents, AutoCloseable {

  private final AnswerStreamListener reader;
  private final UUID runId;
  private final AgentWorkspace workspace;
  private final Thread requestThread = Thread.currentThread();
  private final ActiveAnswerStreams.Registration registration;
  private final ScheduledFuture<?> heartbeat;
  private final Object stopLock = new Object();
  private volatile boolean readerGone;
  private volatile boolean shuttingDown;
  // guarded by stopLock
  private boolean inModelCall;
  private boolean keeping;
  private boolean closed;

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
    synchronized (stopLock) {
      if (keeping || closed) {
        return;
      }
      shuttingDown = true;
      if (inModelCall) {
        requestThread.interrupt();
      }
    }
  }

  private void ping() {
    if (readerGone) {
      return;
    }
    try {
      reader.ping();
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
  public void lastCheckpoint() {
    synchronized (stopLock) {
      checkpoint();
      keeping = true;
    }
  }

  @Override
  public <T> T modelCall(Supplier<T> call) {
    synchronized (stopLock) {
      if (shuttingDown) {
        throw ActiveAnswerStreams.shuttingDown();
      }
      inModelCall = true;
    }
    try {
      return call.get();
    } finally {
      synchronized (stopLock) {
        inModelCall = false;
        // an interrupt that came as the call returned must not reach the next blocking step
        if (shuttingDown) {
          Thread.interrupted();
        }
      }
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

  /** Stops the pings and the shutdown hook; no interrupt reaches the thread after this. */
  @Override
  public void close() {
    heartbeat.cancel(false);
    registration.close();
    synchronized (stopLock) {
      closed = true;
      if (shuttingDown) {
        Thread.interrupted();
      }
    }
  }
}
