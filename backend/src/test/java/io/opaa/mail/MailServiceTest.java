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
 * nothing went out, and it writes the send status that makes a failure visible on the settings page
 * and in the health endpoint.
 *
 * <p>The transport is mocked here on purpose; that a real SMTP conversation works is proved by
 * {@code MailGreenMailIntegrationTest} against an in-JVM server.
 */
@ExtendWith(MockitoExtension.class)
class MailServiceTest {

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

    SendResult result =
        mailService.send(MailTemplateKey.TEST_MAIL, Locale.GERMAN, "erika@example.org", Map.of());

    assertThat(result).isInstanceOf(SendResult.Skipped.class);
    assertThat(result.isSent()).isFalse();
    assertThat(result.reasonOrNull()).contains("nicht eingerichtet");
    verify(settingsService, never()).recordSendOutcome(any(), anyString());
  }

  @Test
  void refusesASendWithoutARecipientAndRecordsItAsAFailure() {
    SendResult result = mailService.send(MailTemplateKey.TEST_MAIL, Locale.GERMAN, "  ", Map.of());

    assertThat(result).isInstanceOf(SendResult.Failed.class);
    verify(settingsService).recordSendOutcome(any(), anyString());
  }

  @Test
  void turnsAFailedRenderIntoAFailedResultRatherThanSendingAnIncompleteMail() {
    when(senderProvider.isEnabled()).thenReturn(true);
    when(templates.render(any(), anyString(), any()))
        .thenThrow(new IllegalStateException("No method or field with name 'actionUrl'"));

    SendResult result =
        mailService.send(
            MailTemplateKey.PASSWORD_RESET, Locale.GERMAN, "erika@example.org", Map.of());

    assertThat(result).isInstanceOf(SendResult.Failed.class);
    assertThat(result.reasonOrNull()).contains("Vorlage").contains("actionUrl");
    verify(sender, never()).send(any(MimeMessage.class));
  }

  @Test
  void reportsTheInnermostCauseOfAFailedSendAndRecordsIt() {
    stubConfiguredSender();
    doThrow(new MailSendException("failed messages", new RuntimeException("Connection refused")))
        .when(sender)
        .send(any(MimeMessage.class));

    SendResult result =
        mailService.send(MailTemplateKey.TEST_MAIL, Locale.GERMAN, "erika@example.org", Map.of());

    assertThat(result).isInstanceOf(SendResult.Failed.class);
    assertThat(result.reasonOrNull()).contains("Connection refused");
    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(settingsService).recordSendOutcome(any(), reason.capture());
    assertThat(reason.getValue()).contains("Connection refused");
  }

  @Test
  void sendsAMultipartMessageWithBothPartsAndRecordsTheSuccess() throws Exception {
    stubConfiguredSender();

    SendResult result =
        mailService.send(MailTemplateKey.TEST_MAIL, Locale.GERMAN, "erika@example.org", Map.of());

    assertThat(result).isEqualTo(new SendResult.Sent("erika@example.org"));
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
  void shortensTheRecipientForTheLogKeepingOnlyTheDomain() {
    assertThat(MailRecipients.shortened("erika.mustermann@amt.example"))
        .isEqualTo("e***@amt.example");
    assertThat(MailRecipients.shortened("keindomain")).isEqualTo("***");
    assertThat(MailRecipients.shortened(null)).isEqualTo("-");
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
