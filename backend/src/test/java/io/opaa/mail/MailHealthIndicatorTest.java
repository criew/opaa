package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.opaa.api.types.MailEncryption;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Status;

/**
 * #1536, ADR-0033 Entscheidung 10: the {@code mail} indicator reports state and nothing else - the
 * cause belongs on the settings page behind {@code SYSTEM_ADMIN}.
 *
 * <p>The distinction that matters operationally: a deployment without a mail server is {@code
 * UNKNOWN}, a deployment whose last attempt failed is {@code DOWN}. Which group that status lands
 * in is {@link MailHealthGroupTest}'s subject.
 */
@ExtendWith(MockitoExtension.class)
class MailHealthIndicatorTest {

  private static final Instant EARLIER = Instant.parse("2026-09-11T08:00:00Z");
  private static final Instant LATER = Instant.parse("2026-09-11T09:00:00Z");

  @Mock private MailSettingsService settingsService;

  @InjectMocks private MailHealthIndicator indicator;

  @Test
  void reportsUnknownWhileSmtpIsNotConfiguredBecauseThatIsASupportedState() {
    when(settingsService.snapshot())
        .thenReturn(
            new MailSettingsSnapshot(
                false, null, null, null, null, MailEncryption.STARTTLS, null, null));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
  }

  @Test
  void reportsUnknownWhileConfiguredButNothingHasBeenSentYet() {
    configured();
    when(settingsService.status()).thenReturn(new MailSendStatus(null, null, null));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UNKNOWN);
  }

  @Test
  void reportsUpAfterASuccessfulSend() {
    configured();
    when(settingsService.status()).thenReturn(new MailSendStatus(LATER, null, null));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void reportsDownWhenTheLastAttemptFailedAndCarriesNoDetails() {
    configured();
    when(settingsService.status())
        .thenReturn(new MailSendStatus(EARLIER, LATER, "Connection refused"));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(indicator.health().getDetails()).isEmpty();
  }

  @Test
  void treatsAFailureFollowedByASuccessAsHistoryRatherThanAsAFault() {
    configured();
    when(settingsService.status())
        .thenReturn(new MailSendStatus(LATER, EARLIER, "Connection refused"));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  /** A health endpoint answers an unreadable configuration with DOWN, never with a 500. */
  @Test
  void reportsDownRatherThanRaisingWhenTheSettingsCannotBeRead() {
    when(settingsService.snapshot())
        .thenThrow(new IllegalStateException("OPAA_SETTINGS_ENCRYPTION_KEY ist nicht gesetzt"));

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(indicator.health().getDetails()).isEmpty();
  }

  private void configured() {
    when(settingsService.snapshot())
        .thenReturn(
            new MailSettingsSnapshot(
                true,
                "smtp.intern.example",
                587,
                null,
                null,
                MailEncryption.STARTTLS,
                "opaa@intern.example",
                null));
  }
}
