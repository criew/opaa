package io.opaa.query.answer;

import io.opaa.common.ServiceUnavailableException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The answer streams of this instance (ADR-0053): process-local on purpose, because a stream lives
 * within one HTTP request and is never resumed elsewhere. On shutdown every stream is ended at once
 * with {@link #SHUTTING_DOWN} and no new one starts, in a phase before the web server's graceful
 * shutdown, so that the stop does not wait for an answer that may take minutes.
 */
@Component
public class ActiveAnswerStreams implements SmartLifecycle {

  /** The code of the refusal and of the closing event when the instance stops. */
  public static final String SHUTTING_DOWN = "SHUTTING_DOWN";

  static final String SHUTTING_DOWN_MESSAGE =
      "Die Antwort wurde abgebrochen, weil der Dienst neu startet. Bitte die Frage erneut senden.";

  private final Set<Registration> streams = ConcurrentHashMap.newKeySet();
  private volatile boolean running;
  private volatile boolean closed;

  /**
   * Registers a stream; {@code onShutdown} ends it from the stopping thread.
   *
   * @throws ServiceUnavailableException with {@link #SHUTTING_DOWN} once the instance stops
   */
  Registration register(Runnable onShutdown) {
    Registration registration = new Registration(onShutdown);
    streams.add(registration);
    if (closed) {
      streams.remove(registration);
      throw shuttingDown();
    }
    return registration;
  }

  static ServiceUnavailableException shuttingDown() {
    return new ServiceUnavailableException(SHUTTING_DOWN_MESSAGE, SHUTTING_DOWN);
  }

  /** How many streams are open; observable by tests. */
  int openStreams() {
    return streams.size();
  }

  @Override
  public void start() {
    closed = false;
    running = true;
  }

  @Override
  public void stop() {
    closed = true;
    running = false;
    for (Registration registration : streams) {
      registration.onShutdown.run();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * The phase of Spring Boot's graceful web server shutdown ({@code DEFAULT_PHASE - 1024}); a
   * higher phase stops first.
   */
  static final int WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE = SmartLifecycle.DEFAULT_PHASE - 1024;

  /** Right before the graceful shutdown of the web server, which would otherwise wait for us. */
  @Override
  public int getPhase() {
    return WEB_SERVER_GRACEFUL_SHUTDOWN_PHASE + 1;
  }

  /** One open stream; closed when the stream ends, however it ends. */
  final class Registration implements AutoCloseable {

    private final Runnable onShutdown;

    private Registration(Runnable onShutdown) {
      this.onShutdown = onShutdown;
    }

    @Override
    public void close() {
      streams.remove(this);
    }
  }
}
