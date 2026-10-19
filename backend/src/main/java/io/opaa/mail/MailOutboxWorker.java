package io.opaa.mail;

import io.opaa.compliance.DataFlow;
import io.opaa.mail.MailOutbox.Batch;
import io.opaa.mail.MailOutbox.Claimed;
import io.opaa.mail.MailOutbox.Unreadable;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers the mail outbox (ADR-0053, Entscheidung 11) - on every instance, on its own scheduler
 * thread (ADR-0021: one worker per process; the instances coordinate through the row claim in
 * {@link MailOutbox}, not through a lock on this run).
 *
 * <p>Promise: at most one delivery attempt of a row at a time, at least one delivery. A worker
 * claims one row at a time for {@link #lease}; a crash between the mail server's acceptance and the
 * deletion can send a mail twice, under the same {@code Message-ID}. A failed attempt is retried
 * after a doubling delay, up to {@link #MAX_ATTEMPTS}; then the row is dropped with an ERROR line.
 * While mail is switched off nothing is claimed - the rows wait.
 *
 * <p>Runs after the commit that queued a mail and every {@link #POLL_SECONDS} seconds for rows of
 * other instances, retries and claims a dead worker left behind. On shutdown it claims nothing
 * more; a row whose delivery is cut off is delivered after its lease by any instance.
 */
@DataFlow("mail")
@Component
public class MailOutboxWorker {

  static final int MAX_ATTEMPTS = 8;
  static final Duration FIRST_RETRY = Duration.ofMinutes(1);
  static final Duration LONGEST_RETRY = Duration.ofHours(1);
  static final long POLL_SECONDS = 15;
  private static final Duration SHORTEST_LEASE = Duration.ofMinutes(5);
  private static final int READS_PER_DELIVERY = 10;

  private static final Logger log = LoggerFactory.getLogger(MailOutboxWorker.class);

  private final MailOutbox outbox;
  private final MailService mail;
  private final MailSenderProvider senderProvider;
  private final TaskScheduler scheduler;
  private final Duration lease;
  private final AtomicBoolean runRequested = new AtomicBoolean();
  private volatile boolean stopping;

  @Autowired
  MailOutboxWorker(
      MailOutbox outbox,
      MailService mail,
      MailSenderProvider senderProvider,
      @Qualifier(MailOutboxConfiguration.SCHEDULER) TaskScheduler scheduler,
      SmtpProperties smtp) {
    this(outbox, mail, senderProvider, scheduler, leaseFor(smtp));
  }

  MailOutboxWorker(
      MailOutbox outbox,
      MailService mail,
      MailSenderProvider senderProvider,
      TaskScheduler scheduler,
      Duration lease) {
    this.outbox = outbox;
    this.mail = mail;
    this.senderProvider = senderProvider;
    this.scheduler = scheduler;
    this.lease = lease;
  }

  /**
   * Longer than any one delivery the SMTP timeouts allow - a conversation is a connect, a handful
   * of commands and the body - so a live worker's claim never lapses under it.
   */
  static Duration leaseFor(SmtpProperties smtp) {
    Duration delivery =
        smtp.connectTimeout()
            .plus(smtp.writeTimeout())
            .plus(smtp.readTimeout().multipliedBy(READS_PER_DELIVERY));
    return delivery.compareTo(SHORTEST_LEASE) > 0 ? delivery : SHORTEST_LEASE;
  }

  /** The delay after the {@code attempts}-th failed attempt: 1, 2, 4 ... minutes, at most 1 h. */
  static Duration retryDelay(int attempts) {
    int doublings = Math.max(0, Math.min(attempts - 1, 20));
    Duration delay = FIRST_RETRY.multipliedBy(1L << doublings);
    return delay.compareTo(LONGEST_RETRY) > 0 ? LONGEST_RETRY : delay;
  }

  @TransactionalEventListener(fallbackExecution = true)
  public void onQueued(MailQueuedEvent event) {
    requestRun();
  }

  @Scheduled(
      fixedDelay = POLL_SECONDS,
      initialDelay = POLL_SECONDS,
      timeUnit = java.util.concurrent.TimeUnit.SECONDS,
      scheduler = MailOutboxConfiguration.SCHEDULER)
  public void poll() {
    deliverDue();
  }

  /** One run on the worker thread; several requests while one waits collapse into it. */
  void requestRun() {
    if (stopping || !runRequested.compareAndSet(false, true)) {
      return;
    }
    try {
      scheduler.schedule(
          () -> {
            runRequested.set(false);
            deliverDue();
          },
          Instant.now());
    } catch (TaskRejectedException shuttingDown) {
      runRequested.set(false);
    }
  }

  /** Delivers every due row, one claim at a time, until none is due or the worker stops. */
  void deliverDue() {
    try {
      while (!stopping && senderProvider.isEnabled()) {
        Batch batch = outbox.claimDue(1, lease);
        if (batch.isEmpty()) {
          return;
        }
        batch.unreadable().forEach(this::giveUp);
        batch.claimed().forEach(this::deliver);
      }
    } catch (RuntimeException e) {
      log.warn(
          "Mail outbox run stopped with {}: {}; the remaining mail waits for the next run",
          e.getClass().getName(),
          MailRecipients.maskAddresses(e.getMessage()));
    }
  }

  private void deliver(Claimed claimed) {
    SendResult result = mail.deliver(claimed);
    switch (result) {
      case SendResult.Sent ignored -> outbox.delete(claimed.id());
      case SendResult.Skipped skipped ->
          outbox.reschedule(claimed.id(), claimed.attempts(), FIRST_RETRY, skipped.reason());
      case SendResult.Failed failed -> failed(claimed, failed.reason());
    }
  }

  private void failed(Claimed claimed, String maskedReason) {
    if (claimed.attempts() < MAX_ATTEMPTS) {
      outbox.reschedule(
          claimed.id(), claimed.attempts(), retryDelay(claimed.attempts()), maskedReason);
      return;
    }
    outbox.delete(claimed.id());
    log.error(
        "Mail {} (template {}, recipient {}) dropped after {} failed attempts: {}",
        claimed.id(),
        claimed.key().key(),
        MailRecipients.shortened(claimed.recipient()),
        claimed.attempts(),
        maskedReason);
  }

  /** A row the settings key cannot read is never deliverable; it goes at once. */
  private void giveUp(Unreadable row) {
    outbox.delete(row.id());
    log.error(
        "Mail {} (template {}) dropped: its content cannot be decrypted ({})",
        row.id(),
        row.templateKey(),
        MailRecipients.maskAddresses(row.reason()));
  }

  @PreDestroy
  void stop() {
    stopping = true;
  }
}
