package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.mail.MailOutbox.Batch;
import io.opaa.mail.MailOutbox.Claimed;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

/**
 * The decisions of {@link MailOutboxWorker} on one claimed row (ADR-0053, Entscheidung 11); that
 * several workers share the table correctly is {@code MailOutboxIntegrationTest}'s.
 */
class MailOutboxWorkerTest {

  private static final Duration LEASE = Duration.ofMinutes(5);
  private static final Batch NOTHING = new Batch(List.of(), List.of());

  private final MailOutbox outbox = mock(MailOutbox.class);
  private final MailService mail = mock(MailService.class);
  private final MailSenderProvider senderProvider = mock(MailSenderProvider.class);
  private final MailOutboxWorker worker =
      new MailOutboxWorker(outbox, mail, senderProvider, new ConcurrentTaskScheduler(), LEASE);

  @Test
  void theRetryDelayDoublesFromOneMinuteUpToOneHour() {
    assertThat(MailOutboxWorker.retryDelay(1)).isEqualTo(Duration.ofMinutes(1));
    assertThat(MailOutboxWorker.retryDelay(2)).isEqualTo(Duration.ofMinutes(2));
    assertThat(MailOutboxWorker.retryDelay(6)).isEqualTo(Duration.ofMinutes(32));
    assertThat(MailOutboxWorker.retryDelay(7)).isEqualTo(Duration.ofHours(1));
    assertThat(MailOutboxWorker.retryDelay(40)).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void theLeaseOutlastsTheLongestDeliveryTheTimeoutsAllow() {
    SmtpProperties defaults =
        new SmtpProperties(Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(15));
    SmtpProperties slow =
        new SmtpProperties(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1));

    assertThat(MailOutboxWorker.leaseFor(defaults)).isEqualTo(Duration.ofMinutes(5));
    assertThat(MailOutboxWorker.leaseFor(slow)).isEqualTo(Duration.ofMinutes(12));
  }

  @Test
  void whileMailIsOffNothingIsClaimed() {
    when(senderProvider.isEnabled()).thenReturn(false);

    worker.deliverDue();

    verify(outbox, never()).claimDue(anyInt(), any());
  }

  @Test
  void aDeliveredRowIsDeleted() {
    Claimed row = claimed(1);
    when(senderProvider.isEnabled()).thenReturn(true);
    when(outbox.claimDue(1, LEASE)).thenReturn(new Batch(List.of(row), List.of()), NOTHING);
    when(mail.deliver(row)).thenReturn(new SendResult.Sent("x"));

    worker.deliverDue();

    verify(outbox).delete(row.id());
  }

  @Test
  void aRefusedRowWaitsForItsRetryDelayUntilTheLastAttemptAndIsThenDropped() {
    Claimed third = claimed(3);
    Claimed last = claimed(MailOutboxWorker.MAX_ATTEMPTS);
    when(senderProvider.isEnabled()).thenReturn(true);
    when(outbox.claimDue(1, LEASE))
        .thenReturn(
            new Batch(List.of(third), List.of()), new Batch(List.of(last), List.of()), NOTHING);
    when(mail.deliver(any())).thenReturn(new SendResult.Failed("Connection refused"));

    worker.deliverDue();

    verify(outbox).reschedule(third.id(), 3, Duration.ofMinutes(4), "Connection refused");
    verify(outbox, never()).delete(third.id());
    verify(outbox).delete(last.id());
  }

  @Test
  void aRowWhoseContentCannotBeReadIsDroppedAtOnce() {
    UUID id = UUID.randomUUID();
    when(senderProvider.isEnabled()).thenReturn(true);
    when(outbox.claimDue(1, LEASE))
        .thenReturn(
            new Batch(
                List.of(), List.of(new MailOutbox.Unreadable(id, "password-reset", 1, "kaputt"))),
            NOTHING);

    worker.deliverDue();

    verify(outbox).delete(id);
    verify(mail, never()).deliver(any());
  }

  @Test
  void aStoppedWorkerClaimsNothingMore() {
    when(senderProvider.isEnabled()).thenReturn(true);

    worker.stop();
    worker.deliverDue();

    verify(outbox, never()).claimDue(anyInt(), any());
  }

  private static Claimed claimed(int attempts) {
    return new Claimed(
        UUID.randomUUID(),
        MailTemplateKey.PASSWORD_RESET,
        "erika@amt.example",
        new RenderedMail("Betreff", "Text", null),
        attempts);
  }
}
