package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.MailEncryption;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * #1536, ADR-0033 Entscheidung 10: {@link MailService}'s contract - it never throws, it reports why
 * nothing went out, it lets no recipient address into the cause it hands on, and it writes the send
 * status only for a failure of the mail server itself.
 *
 * <p>The transport is mocked here on purpose; that a real SMTP conversation works is proved by
 * {@code MailGreenMailIntegrationTest} against an in-JVM server.
 */
@ExtendWith(MockitoExtension.class)
class MailServiceTest {

  private static final String RECIPIENT = "erika.mustermann@amt.example";

  @Mock private MailSenderProvider senderProvider;
  @Mock private MailTemplateService templates;
  @Mock private MailSettingsService settingsService;
  @Mock private JavaMailSender sender;

  @InjectMocks private MailService mailService;

  private static final MailSettingsSnapshot CONFIGURED =
      new MailSettingsSnapshot(
          true,
          "smtp.intern.example",
          587,
          null,
          null,
          MailEncryption.STARTTLS,
          "opaa@intern.example",
          "OPAA");

  @Test
  void skipsWithoutThrowingWhileSmtpIsNotConfigured() {
    when(senderProvider.isEnabled()).thenReturn(false);

    SendResult result = send(MailTemplateKey.TEST_MAIL);

    assertThat(result).isInstanceOf(SendResult.Skipped.class);
    assertThat(result.isSent()).isFalse();
    assertThat(result.reasonOrNull()).contains("nicht eingerichtet");
    verify(settingsService, never()).recordSendOutcome(any(), anyString());
  }

  /**
   * Regression guard for #1559 review, MEDIUM 5: a missing recipient is not a fault of the mail
   * server, so it must not appear as one in {@code last_failure_*}.
   */
  @Test
  void refusesASendWithoutARecipientWithoutTouchingTheMailServerStatus() {
    SendResult result = mailService.send(MailTemplateKey.TEST_MAIL, Locale.GERMAN, "  ", Map.of());

    assertThat(result).isInstanceOf(SendResult.Failed.class);
    verify(settingsService, never()).recordSendOutcome(any(), any());
  }

  @Test
  void turnsAFailedRenderIntoAFailedResultWithoutTouchingTheMailServerStatus() {
    when(senderProvider.isEnabled()).thenReturn(true);
    when(templates.render(any(), anyString(), any()))
        .thenThrow(new IllegalStateException("No method or field with name 'actionUrl'"));

    SendResult result = send(MailTemplateKey.PASSWORD_RESET);

    assertThat(result).isInstanceOf(SendResult.Failed.class);
    assertThat(result.reasonOrNull()).contains("Vorlage").contains("actionUrl");
    verify(sender, never()).send(any(MimeMessage.class));
    verify(settingsService, never()).recordSendOutcome(any(), any());
  }

  /**
   * Regression guard for #1559 review, HIGH 2: {@code snapshot()} raises whenever {@code
   * OPAA_SETTINGS_ENCRYPTION_KEY} is missing or was rotated. Before the fix that reached the caller
   * - a 500 on the settings test, and from #1537 on a failed account creation.
   */
  @Test
  void reportsAnUnreadableConfigurationAsFailedInsteadOfThrowing() {
    when(senderProvider.isEnabled())
        .thenThrow(new IllegalStateException("OPAA_SETTINGS_ENCRYPTION_KEY ist nicht gesetzt"));

    SendResult result = send(MailTemplateKey.TEST_MAIL);

    assertThat(result).isInstanceOf(SendResult.Failed.class);
    assertThat(result.reasonOrNull()).contains("OPAA_SETTINGS_ENCRYPTION_KEY");
    verify(settingsService, never()).recordSendOutcome(any(), any());
  }

  /** A status write that fails must not turn a sent mail into a failed one. */
  @Test
  void stillReportsSentWhenTheStatusWriteItselfFails() {
    stubConfiguredSender();
    doThrow(new IllegalStateException("Datenbank nicht erreichbar"))
        .when(settingsService)
        .recordSendOutcome(any(), isNull());

    assertThat(send(MailTemplateKey.TEST_MAIL)).isEqualTo(new SendResult.Sent(RECIPIENT));
  }

  @Test
  void reportsTheInnermostCauseOfAFailedSendAndRecordsIt() {
    stubConfiguredSender();
    doThrow(new MailSendException("failed messages", new RuntimeException("Connection refused")))
        .when(sender)
        .send(any(MimeMessage.class));

    SendResult result = send(MailTemplateKey.TEST_MAIL);

    assertThat(result).isInstanceOf(SendResult.Failed.class);
    assertThat(result.reasonOrNull()).contains("Connection refused");
    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(settingsService).recordSendOutcome(any(), reason.capture());
    assertThat(reason.getValue()).contains("Connection refused");
  }

  /**
   * Regression guard for #1559 review, HIGH 1: {@code JavaMailSenderImpl} throws {@link
   * MailSendException} built from the failed-messages map <em>without</em> a cause, and its own
   * message is the list of rejected recipients. Walking {@code getCause()} therefore used to put
   * the address into the log, into {@code last_failure_reason} and into the API response.
   */
  @Test
  void unwrapsARejectedRecipientThroughTheFailedMessagesAndNeverNamesTheAddress() {
    stubConfiguredSender();
    MimeMessage failed = new MimeMessage(Session.getInstance(new Properties()));
    doThrow(
            new MailSendException(
                Map.of(
                    failed,
                    new SendFailedException(
                        "550 5.1.1 <" + RECIPIENT + ">: Recipient address rejected"))))
        .when(sender)
        .send(any(MimeMessage.class));

    SendResult result = send(MailTemplateKey.TEST_MAIL);

    assertThat(result.reasonOrNull())
        .contains("Recipient address rejected")
        .doesNotContain(RECIPIENT)
        .doesNotContain("erika.mustermann")
        .contains("e***@amt.example");
    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(settingsService).recordSendOutcome(any(), reason.capture());
    assertThat(reason.getValue()).doesNotContain("erika.mustermann");
  }

  @Test
  void sendsAMultipartMessageWithBothPartsAndRecordsTheSuccess() throws Exception {
    stubConfiguredSender();

    SendResult result = send(MailTemplateKey.TEST_MAIL);

    assertThat(result).isEqualTo(new SendResult.Sent(RECIPIENT));
    ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
    verify(sender).send(message.capture());
    // saveChanges() is what turns the composed parts into the message's own headers; the real
    // JavaMailSenderImpl does it on send, and the mocked one here does not.
    message.getValue().saveChanges();
    assertThat(message.getValue().getSubject()).isEqualTo("Betreff");
    assertThat(message.getValue().getContentType()).startsWith("multipart/alternative");
    assertThat(message.getValue().getFrom()[0].toString()).contains("opaa@intern.example");
    verify(settingsService).recordSendOutcome(any(), isNull());
  }

  @Test
  void shortensASingleRecipientForTheLogKeepingOnlyTheDomain() {
    assertThat(MailRecipients.shortened(RECIPIENT)).isEqualTo("e***@amt.example");
    assertThat(MailRecipients.shortened("keindomain")).isEqualTo("***");
    assertThat(MailRecipients.shortened(null)).isEqualTo("-");
  }

  @Test
  void masksEveryAddressInsideAServerMessageAndLeavesTheRestIntact() {
    assertThat(
            MailRecipients.maskAddresses(
                "550 <erika@amt.example>, <max@amt.example>: Recipient address rejected"))
        .isEqualTo("550 <e***@amt.example>, <m***@amt.example>: Recipient address rejected");
    assertThat(MailRecipients.maskAddresses("Connection refused")).isEqualTo("Connection refused");
    assertThat(MailRecipients.maskAddresses(null)).isNull();
  }

  private SendResult send(MailTemplateKey key) {
    return mailService.send(key, Locale.GERMAN, RECIPIENT, Map.of());
  }

  private void stubConfiguredSender() {
    when(senderProvider.isEnabled()).thenReturn(true);
    when(senderProvider.current()).thenReturn(sender);
    when(senderProvider.settings()).thenReturn(CONFIGURED);
    when(sender.createMimeMessage())
        .thenReturn(new MimeMessage(Session.getInstance(new Properties())));
    when(templates.render(eq(MailTemplateKey.TEST_MAIL), eq("de"), any()))
        .thenReturn(new RenderedMail("Betreff", "Textteil", "<p>HTML-Teil</p>"));
  }
}
