package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.api.types.MailEncryption;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * A failed check answers with one of a few fixed classes, so refused, timed out, unknown host and a
 * service's greeting look alike to whoever probes; the cause in the log carries no password.
 */
class MailConnectionTesterTest {

  // regression guard: the answer of the mail check is no oracle for ports and services
  @Test
  void everyCauseFallsIntoOneOfTheFixedClasses() {
    assertThat(classify(new MessagingException("Could not connect", new ConnectException("x"))))
        .isEqualTo(MailConnectionTester.Failure.UNREACHABLE);
    assertThat(classify(new MessagingException("x", new UnknownHostException("intern"))))
        .isEqualTo(MailConnectionTester.Failure.UNREACHABLE);
    assertThat(
            classify(new MessagingException("x", new SocketTimeoutException("Connect timed out"))))
        .isEqualTo(MailConnectionTester.Failure.UNREACHABLE);
    assertThat(classify(new MessagingException("x", new SSLHandshakeException("handshake"))))
        .isEqualTo(MailConnectionTester.Failure.TLS);
    assertThat(
            classify(new MessagingException("STARTTLS is required but host does not support it")))
        .isEqualTo(MailConnectionTester.Failure.TLS);
    assertThat(classify(new AuthenticationFailedException("535 5.7.8 rejected")))
        .isEqualTo(MailConnectionTester.Failure.SIGN_IN_REFUSED);
    assertThat(classify(new MessagingException("Got bad greeting from SMTP host: -ERR redis")))
        .isEqualTo(MailConnectionTester.Failure.NOT_A_MAIL_SERVER);
    assertThat(classify(new MessagingException("x", new SocketTimeoutException("Read timed out"))))
        .isEqualTo(MailConnectionTester.Failure.NOT_A_MAIL_SERVER);
  }

  @Test
  void theLoggedCauseCarriesNeitherThePasswordNorAControlCharacter() {
    assertThat(MailConnectionTester.redacted("535 bad pw geheim\r\nX: 1", "geheim"))
        .isEqualTo("535 bad pw ***??X: 1");
  }

  // regression guard: the password is replaced before the address mask can rewrite it
  @Test
  void anAddressLikePasswordThatTheServerEchoesLeavesNoTraceInTheLog() throws Exception {
    String password = "kw@geheim.zz";
    MailSettingsSnapshot settings =
        new MailSettingsSnapshot(
            true,
            "smtp.intern.example",
            587,
            "kennung",
            password,
            MailEncryption.STARTTLS,
            "opaa@intern.example",
            null);
    JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
    doThrow(new AuthenticationFailedException("535 5.7.8 kennung/" + password + " rejected"))
        .when(sender)
        .testConnection();
    MailSenderProvider senders = mock(MailSenderProvider.class);
    when(senders.transportFor(settings))
        .thenReturn(new MailSenderProvider.Transport(sender, settings));
    MailConnectionTester tester =
        new MailConnectionTester(senders, mock(MailService.class), mock(MailTestService.class));
    Logger logger = (Logger) LoggerFactory.getLogger(MailConnectionTester.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      assertThat(tester.test(settings, null).succeeded()).isFalse();
    } finally {
      logger.detachAppender(logs);
    }

    assertThat(logs.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .contains("535 5.7.8 kennung/*** rejected")
        .doesNotContain("geheim")
        .doesNotContain("kw");
  }

  @Test
  void aShortPasswordIsReplacedWhereItStandsAloneButNotInsideAWord() {
    assertThat(MailConnectionTester.redacted("535 pw ab rejected; abc, pw=ab.", "ab"))
        .isEqualTo("535 pw *** rejected; abc, pw=***.");
    assertThat(MailConnectionTester.redacted("535 user x: x rejected", "x"))
        .isEqualTo("535 user ***: *** rejected");
  }

  @Test
  void theLoggedCauseCarriesNoUnicodeLineBreak() {
    assertThat(MailConnectionTester.redacted("535 a\u0085b\u2028c\u2029d\u001be", "geheim"))
        .isEqualTo("535 a?b?c?d?e");
  }

  private static MailConnectionTester.Failure classify(Throwable error) {
    return MailConnectionTester.classify(error);
  }
}
