package io.opaa.mail;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one thread on which mail leaves <em>after</em> the request that caused it has answered
 * (ADR-0033, Entscheidungen 5 and 11): the emergency sign-in must not wait for a mail server, and
 * the self-service flows must not let their response time betray whether an account exists. A
 * bounded queue keeps a stalled SMTP server from piling up work without limit; a send that finds
 * the queue full is dropped with a WARN line (no address, no template) - {@link MailService} never
 * saw it, so the mail settings' failure record stays as it is. Shut down with the context; a send
 * in flight gets a few seconds to finish.
 */
@Component
public class MailDispatchExecutor implements Executor {

  static final int QUEUE_CAPACITY = 1000;
  private static final long SHUTDOWN_GRACE_SECONDS = 5;

  private static final Logger log = LoggerFactory.getLogger(MailDispatchExecutor.class);

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          runnable -> {
            Thread thread = new Thread(runnable, "mail-dispatch");
            thread.setDaemon(true);
            return thread;
          },
          (runnable, pool) ->
              log.warn(
                  "Mail dispatch queue is full ({} sends pending): a send was dropped",
                  QUEUE_CAPACITY));

  /**
   * A task that fails leaves one ERROR line - the exception's class and its address-masked message
   * - instead of an unmasked stack trace on the thread's stderr; the work it stood for (an account,
   * a link) simply did not happen, which the mail subsystem's records do not know about.
   */
  @Override
  public void execute(Runnable send) {
    executor.execute(
        () -> {
          try {
            send.run();
          } catch (RuntimeException e) {
            log.error(
                "Mail dispatch task failed with {}: {}",
                e.getClass().getName(),
                MailRecipients.maskAddresses(e.getMessage()));
          }
        });
  }

  /** How many sends are waiting - for tests and diagnostics. */
  public int pending() {
    return executor.getQueue().size();
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
        log.warn(
            "Mail dispatch did not finish within {} s; remaining sends are dropped",
            SHUTDOWN_GRACE_SECONDS);
        executor.shutdownNow();
      }
    } catch (InterruptedException interrupted) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
