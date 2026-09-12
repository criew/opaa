package io.opaa.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import io.opaa.api.types.MailEncryption;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsUpdate;
import io.opaa.mail.MailTestSupport;
import io.opaa.organization.Organization;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in-JVM SMTP server (GreenMail) wired into the application's mail settings, for integration
 * tests that prove a flow by the message that arrived. {@link #start} binds a free port and stores
 * it as the SMTP host of {@code mail_settings}; {@link #stop} switches SMTP off again and drops the
 * cached sender. A plain helper, not a Spring bean, so it never changes a context's cache key.
 */
public final class LocalMailbox {

  private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

  private final MailSettingsService mailSettings;
  private final UUID actorId;
  private GreenMail greenMail;

  public LocalMailbox(MailSettingsService mailSettings, UUID actorId) {
    this.mailSettings = mailSettings;
    this.actorId = actorId;
  }

  public void start() {
    greenMail = new GreenMail(new ServerSetup(freePort(), "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    greenMail.setUser("opaa@intern.example", "kennung", "geheim");
    greenMail.start();
    configure(true);
  }

  public void stop() {
    if (greenMail != null) {
      greenMail.stop();
    }
    configure(false);
    MailTestSupport.resetCaches(mailSettings);
  }

  /** Waits up to {@code timeoutMillis} for at least {@code count} messages. */
  public boolean waitFor(int count, long timeoutMillis) {
    return greenMail.waitForIncomingEmail(timeoutMillis, count);
  }

  public MimeMessage[] messages() {
    return greenMail.getReceivedMessages();
  }

  /** The plain-text part of {@code message}. */
  public static String plainText(MimeMessage message) {
    try {
      Object content = message.getContent();
      if (content instanceof MimeMultipart multipart) {
        for (int i = 0; i < multipart.getCount(); i++) {
          Part part = multipart.getBodyPart(i);
          if (part.isMimeType("text/plain")) {
            return part.getContent().toString();
          }
        }
      }
      return content.toString();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read the mail body", e);
    }
  }

  /** The raw action token behind the first {@code token=…} in {@code text}. */
  public static String tokenIn(String text) {
    Matcher matcher = TOKEN_IN_LINK.matcher(text);
    assertThat(matcher.find()).as("token in %s", text).isTrue();
    return matcher.group(1);
  }

  private void configure(boolean enabled) {
    mailSettings.updateSettings(
        Organization.DEFAULT_ID,
        actorId,
        enabled
            ? new MailSettingsUpdate(
                true,
                "127.0.0.1",
                greenMail.getSmtp().getPort(),
                "kennung",
                "geheim",
                MailEncryption.NONE,
                "opaa@intern.example",
                "OPAA")
            : new MailSettingsUpdate(
                false, null, null, null, "", MailEncryption.STARTTLS, null, null));
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
