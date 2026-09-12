package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The reminder window is the calendar day fourteen days after the run's day in the run's zone -
 * across a daylight-saving change the day is 23 or 25 hours long, and two consecutive runs still
 * cover every instant exactly once.
 */
class ExpiryReminderStepTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

  @Test
  void theWindowIsTheCalendarDayFourteenDaysAheadInTheZoneOfTheRun() {
    Instant run = ZonedDateTime.of(2026, 10, 11, 3, 20, 0, 0, BERLIN).toInstant();

    ExpiryReminderStep.Window window = ExpiryReminderStep.windowOf(run, BERLIN);

    // 25 October 2026: the clocks go back, the day has 25 hours
    assertThat(window.from())
        .isEqualTo(LocalDate.of(2026, 10, 25).atStartOfDay(BERLIN).toInstant());
    assertThat(window.to()).isEqualTo(LocalDate.of(2026, 10, 26).atStartOfDay(BERLIN).toInstant());
    assertThat(Duration.between(window.from(), window.to())).isEqualTo(Duration.ofHours(25));
    assertThat(window.contains(window.from())).isTrue();
    assertThat(window.contains(window.to())).isFalse();
  }

  /**
   * The budget is time, not a count: with a mailer slower than the budget the second reminder is
   * not sent, and the abort is an ERROR line naming the number left out.
   */
  @Test
  void stopsAfterTheTimeBudgetAndNamesTheAccountsLeftWithoutAReminder() {
    LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
    UserRepository users = mock(UserRepository.class);
    LocalAccountMailer mailer = mock(LocalAccountMailer.class);
    Instant run = ZonedDateTime.of(2026, 10, 11, 3, 20, 0, 0, BERLIN).toInstant();
    ExpiryReminderStep.Window window = ExpiryReminderStep.windowOf(run, BERLIN);
    User first = User.localAccount("eins@stadt.example", "Eins");
    User second = User.localAccount("zwei@stadt.example", "Zwei");
    LocalCredentials firstRow = activeRow(first, window.from().plusSeconds(60));
    LocalCredentials secondRow = activeRow(second, window.from().plusSeconds(120));
    when(credentials.findByExpiresAtGreaterThanEqualAndExpiresAtLessThan(
            window.from(), window.to()))
        .thenReturn(List.of(firstRow, secondRow));
    when(users.findAllById(any())).thenReturn(List.of(first, second));
    doAnswer(
            invocation -> {
              Thread.sleep(120);
              return null;
            })
        .when(mailer)
        .sendExpiring(any(), any());
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(ExpiryReminderStep.class)).addAppender(logs);

    new ExpiryReminderStep(credentials, users, mailer, BERLIN, Duration.ofMillis(50)).run(run);

    verify(mailer).sendExpiring(first, firstRow.getExpiresAt());
    verify(mailer, never()).sendExpiring(eq(second), any());
    assertThat(logs.list)
        .anyMatch(
            event ->
                event.getLevel() == Level.ERROR
                    && event.getFormattedMessage().contains("1 account(s) expiring")
                    && !event.getFormattedMessage().contains("stadt.example"));
  }

  private static LocalCredentials activeRow(User user, Instant expiresAt) {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    LocalCredentials row = new LocalCredentials(user.getId(), "Test", created);
    row.setPasswordHash("{bcrypt}irrelevant", created);
    row.markEmailVerified(created);
    row.setExpiresAt(expiresAt, created);
    return row;
  }

  @Test
  void consecutiveRunsCoverEveryInstantExactlyOnceAcrossTheChange() {
    Instant dayBefore = ZonedDateTime.of(2026, 10, 10, 3, 20, 0, 0, BERLIN).toInstant();
    Instant day = ZonedDateTime.of(2026, 10, 11, 3, 20, 0, 0, BERLIN).toInstant();
    Instant dayAfter = ZonedDateTime.of(2026, 10, 12, 3, 20, 0, 0, BERLIN).toInstant();

    ExpiryReminderStep.Window first = ExpiryReminderStep.windowOf(dayBefore, BERLIN);
    ExpiryReminderStep.Window second = ExpiryReminderStep.windowOf(day, BERLIN);
    ExpiryReminderStep.Window third = ExpiryReminderStep.windowOf(dayAfter, BERLIN);

    assertThat(first.to()).isEqualTo(second.from());
    assertThat(second.to()).isEqualTo(third.from());
    Instant expiry = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, BERLIN).toInstant();
    assertThat(first.contains(expiry)).isFalse();
    assertThat(second.contains(expiry)).isTrue();
    assertThat(third.contains(expiry)).isFalse();
  }
}
