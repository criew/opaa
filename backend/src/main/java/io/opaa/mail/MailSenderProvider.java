package io.opaa.mail;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link JavaMailSender} the current {@code mail_settings} describe (#1536, ADR-0033
 * Entscheidung 10) and caches it <b>together with the snapshot it was built from</b>: a corrected
 * SMTP host takes effect on the next message, without a restart and without a second {@code
 * AFTER_COMMIT} listener whose order relative to the snapshot rebuild would be undefined.
 *
 * <p>{@link #current()} returns {@code null} while mail is not configured - not an error: a
 * deployment without a mail server is supported and {@link MailService} reports {@code Skipped}.
 *
 * <p>Every connection is bounded by the {@link SmtpProperties} timeouts; the send path is
 * synchronous, so the thread a stalled peer would hold is the caller's.
 */
@Component
public class MailSenderProvider {

  private static final Logger log = LoggerFactory.getLogger(MailSenderProvider.class);

  private static final String ENCODING = "UTF-8";

  /** A built transport and the exact snapshot instance it was built from. */
  private record Cached(MailSettingsSnapshot from, JavaMailSender sender) {}

  private final MailSettingsService settingsService;
  private final SmtpProperties properties;
  private final AtomicReference<Cached> cached = new AtomicReference<>();

  public MailSenderProvider(MailSettingsService settingsService, SmtpProperties properties) {
    this.settingsService = settingsService;
    this.properties = properties;
  }

  /** Whether a send should be attempted: the master switch is on and a host is configured. */
  public boolean isEnabled() {
    return settingsService.snapshot().sendable();
  }

  /** The configuration a message's envelope is built from (sender address and display name). */
  public MailSettingsSnapshot settings() {
    return settingsService.snapshot();
  }

  /**
   * The transport for the current settings, built on first use and rebuilt as soon as {@link
   * MailSettingsService#snapshot()} hands out a different instance; {@code null} while mail is not
   * configured. Compared by identity on purpose - the snapshot is replaced wholesale after every
   * committed change, so identity is the cheapest exact answer to "are these still the settings
   * this transport was built from".
   */
  public JavaMailSender current() {
    MailSettingsSnapshot snapshot = settingsService.snapshot();
    if (!snapshot.sendable()) {
      return null;
    }
    Cached existing = cached.get();
    if (existing != null && existing.from() == snapshot) {
      return existing.sender();
    }
    Cached rebuilt = new Cached(snapshot, build(snapshot));
    cached.set(rebuilt);
    return rebuilt.sender();
  }

  private JavaMailSender build(MailSettingsSnapshot snapshot) {
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(snapshot.host());
    if (snapshot.port() != null) {
      sender.setPort(snapshot.port());
    }
    sender.setDefaultEncoding(ENCODING);
    if (snapshot.authenticated()) {
      sender.setUsername(snapshot.username());
      sender.setPassword(snapshot.password());
    }

    Properties props = sender.getJavaMailProperties();
    props.put("mail.transport.protocol", "smtp");
    props.put("mail.smtp.auth", String.valueOf(snapshot.authenticated()));
    props.put("mail.smtp.connectiontimeout", millis(properties.connectTimeout()));
    props.put("mail.smtp.timeout", millis(properties.readTimeout()));
    props.put("mail.smtp.writetimeout", millis(properties.writeTimeout()));
    applyEncryption(props, snapshot);
    return sender;
  }

  /**
   * STARTTLS is configured as <em>required</em>, never merely enabled: a server that does not offer
   * it must fail the connection rather than continue in clear.
   */
  private static void applyEncryption(Properties props, MailSettingsSnapshot snapshot) {
    switch (snapshot.encryption()) {
      case SSL -> props.put("mail.smtp.ssl.enable", "true");
      case STARTTLS -> {
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
      }
      case NONE ->
          log.info(
              "SMTP-Verschluesselung ist auf NONE gestellt - die Verbindung zu {} ist"
                  + " unverschluesselt",
              snapshot.host());
    }
  }

  private static String millis(Duration duration) {
    return Long.toString(duration.toMillis());
  }
}
