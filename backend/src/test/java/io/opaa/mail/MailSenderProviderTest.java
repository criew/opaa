package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.opaa.api.types.MailEncryption;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * #1536: what {@link MailSenderProvider} builds from a snapshot - the encryption mode translated
 * into Jakarta Mail properties, the timeouts applied, the authentication flag derived from the
 * username - and that a committed settings change throws the cached transport away.
 */
@ExtendWith(MockitoExtension.class)
class MailSenderProviderTest {

  @Mock private MailSettingsService settingsService;

  private MailSenderProvider provider;

  @BeforeEach
  void setUp() {
    provider =
        new MailSenderProvider(
            settingsService,
            new SmtpProperties(
                Duration.ofSeconds(7), Duration.ofSeconds(11), Duration.ofSeconds(13)));
  }

  @Test
  void reportsNotEnabledAndBuildsNothingWhileSmtpIsSwitchedOff() {
    when(settingsService.snapshot()).thenReturn(MailSettingsSnapshot.DISABLED);

    assertThat(provider.isEnabled()).isFalse();
    assertThat(provider.current()).isNull();
  }

  @Test
  void reportsNotEnabledWhenTheSwitchIsOnButNoHostIsConfigured() {
    when(settingsService.snapshot())
        .thenReturn(
            new MailSettingsSnapshot(
                true, "  ", 587, null, null, MailEncryption.STARTTLS, "opaa@example.org", null));

    assertThat(provider.isEnabled()).isFalse();
    assertThat(provider.current()).isNull();
  }

  @Test
  void requiresStartTlsRatherThanMerelyEnablingItSoNoConnectionSilentlyStaysInClear() {
    Properties props = propertiesFor(snapshot(MailEncryption.STARTTLS, "kennung"));

    assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
    assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
    assertThat(props.getProperty("mail.smtp.ssl.enable")).isNull();
  }

  @Test
  void usesImplicitTlsForSsl() {
    Properties props = propertiesFor(snapshot(MailEncryption.SSL, "kennung"));

    assertThat(props.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
    assertThat(props.getProperty("mail.smtp.starttls.enable")).isNull();
  }

  @Test
  void switchesOffBothTlsModesForAnUnencryptedRelay() {
    Properties props = propertiesFor(snapshot(MailEncryption.NONE, "kennung"));

    assertThat(props.getProperty("mail.smtp.ssl.enable")).isNull();
    assertThat(props.getProperty("mail.smtp.starttls.enable")).isNull();
  }

  @Test
  void appliesTheConfiguredTimeoutsSoAStalledPeerCannotHoldTheCallersThread() {
    Properties props = propertiesFor(snapshot(MailEncryption.STARTTLS, "kennung"));

    assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("7000");
    assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("11000");
    assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("13000");
  }

  @Test
  void authenticatesExactlyWhenAUsernameIsConfigured() {
    JavaMailSenderImpl authenticated =
        (JavaMailSenderImpl) build(snapshot(MailEncryption.STARTTLS, "kennung"));
    assertThat(authenticated.getUsername()).isEqualTo("kennung");
    assertThat(authenticated.getJavaMailProperties().getProperty("mail.smtp.auth"))
        .isEqualTo("true");

    JavaMailSenderImpl anonymous =
        (JavaMailSenderImpl) build(snapshot(MailEncryption.STARTTLS, null));
    assertThat(anonymous.getUsername()).isNull();
    assertThat(anonymous.getJavaMailProperties().getProperty("mail.smtp.auth")).isEqualTo("false");
  }

  @Test
  void keepsTheSameTransportUntilTheSettingsChangeAndBuildsAFreshOneAfterwards() {
    when(settingsService.snapshot()).thenReturn(snapshot(MailEncryption.STARTTLS, "kennung"));

    JavaMailSender first = provider.current();
    assertThat(provider.current()).isSameAs(first);

    provider.onSettingsChanged(new MailSettingsChangedEvent());

    assertThat(provider.current()).isNotSameAs(first);
  }

  private JavaMailSender build(MailSettingsSnapshot snapshot) {
    when(settingsService.snapshot()).thenReturn(snapshot);
    provider.invalidate();
    return provider.current();
  }

  private Properties propertiesFor(MailSettingsSnapshot snapshot) {
    return ((JavaMailSenderImpl) build(snapshot)).getJavaMailProperties();
  }

  private static MailSettingsSnapshot snapshot(MailEncryption encryption, String username) {
    return new MailSettingsSnapshot(
        true,
        "smtp.intern.example",
        587,
        username,
        username == null ? null : "geheim",
        encryption,
        "opaa@intern.example",
        "OPAA");
  }
}
