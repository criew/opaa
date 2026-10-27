package io.opaa.mail;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

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

  private static MailConnectionTester.Failure classify(Throwable error) {
    return MailConnectionTester.classify(error);
  }
}
