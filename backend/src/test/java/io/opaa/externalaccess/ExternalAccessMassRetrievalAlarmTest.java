package io.opaa.externalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.externalaccess.ExternalAccessSettings.Values;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExternalAccessMassRetrievalAlarmTest {

  private static final Instant START = Instant.parse("2026-09-18T02:00:00Z");
  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private ExternalAccessSettingsService settings;
  private UserRepository users;
  private NotificationService notifications;
  private MovableClock clock;
  private ExternalAccessMassRetrievalAlarm alarm;
  private UUID administratorId;

  @BeforeEach
  void setUp() {
    settings = mock(ExternalAccessSettingsService.class);
    users = mock(UserRepository.class);
    notifications = mock(NotificationService.class);
    clock = new MovableClock(START);
    threshold(3);
    User administrator = new User("admin-subject", "test-issuer", "admin@example.com", "Admin");
    administratorId = administrator.getId();
    when(users.findByOrganizationIdAndSystemRole(ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN))
        .thenReturn(List.of(administrator));
    alarm = new ExternalAccessMassRetrievalAlarm(settings, users, notifications, clock);
  }

  private void threshold(int value) {
    when(settings.current())
        .thenReturn(
            new ExternalAccessSettingsService.View(
                new Values(true, 90, 60, List.of(), value, ""), START, null));
  }

  @Test
  void exceedingTheThresholdRaisesExactlyOneMessageToTheSystemAdministration() {
    UUID token = UUID.randomUUID();

    for (int i = 0; i < 4; i++) {
      alarm.record(ORGANIZATION_ID, token);
    }

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(notifications)
        .notify(
            eq(ORGANIZATION_ID),
            eq(administratorId),
            eq(NotificationType.EXTERNAL_ACCESS_MASS_RETRIEVAL),
            eq(AuditObjectType.SYSTEM_SETTING),
            eq(token),
            anyString(),
            body.capture());
    // The token id is what makes the alert actionable; it is the only identifier in the message.
    assertThat(body.getValue()).contains(token.toString()).contains("3").contains("4");
  }

  @Test
  void aSustainedEventDoesNotDecayIntoASeriesOfMessages() {
    UUID token = UUID.randomUUID();

    for (int i = 0; i < 60; i++) {
      alarm.record(ORGANIZATION_ID, token);
    }
    clock.advance(ExternalAccessMassRetrievalAlarm.COOLDOWN.minusMinutes(1));
    for (int i = 0; i < 60; i++) {
      alarm.record(ORGANIZATION_ID, token);
    }

    verify(notifications, times(1))
        .notify(any(), any(), any(), any(), any(), anyString(), anyString());
  }

  @Test
  void afterTheCooldownAFurtherEventRaisesAgain() {
    UUID token = UUID.randomUUID();

    for (int i = 0; i < 4; i++) {
      alarm.record(ORGANIZATION_ID, token);
    }
    clock.advance(ExternalAccessMassRetrievalAlarm.COOLDOWN.plusMinutes(1));
    for (int i = 0; i < 4; i++) {
      alarm.record(ORGANIZATION_ID, token);
    }

    verify(notifications, times(2))
        .notify(any(), any(), any(), any(), any(), anyString(), anyString());
  }

  @Test
  void theCountIsChannelWideAndSlidesOutOfTheWindow() {
    for (int i = 0; i < 3; i++) {
      alarm.record(ORGANIZATION_ID, UUID.randomUUID());
    }
    clock.advance(ExternalAccessMassRetrievalAlarm.WINDOW.plusMinutes(1));
    for (int i = 0; i < 3; i++) {
      alarm.record(ORGANIZATION_ID, UUID.randomUUID());
    }

    verifyNoInteractions(notifications);
  }

  @Test
  void aSignedInPersonWithoutATokenIsNotCountedAtAll() {
    for (int i = 0; i < 100; i++) {
      alarm.record(ORGANIZATION_ID, null);
    }

    verifyNoInteractions(notifications);
    verifyNoInteractions(users);
  }

  @Test
  void theCountLivesInMemoryAndDoesNotSurviveAFreshInstance() {
    UUID token = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      alarm.record(ORGANIZATION_ID, token);
    }

    var restarted = new ExternalAccessMassRetrievalAlarm(settings, users, notifications, clock);
    restarted.record(ORGANIZATION_ID, token);

    verifyNoInteractions(notifications);
  }

  /** A clock a test moves by hand; {@link Clock#millis()} is all the alarm reads. */
  private static final class MovableClock extends Clock {

    private Instant now;

    private MovableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
