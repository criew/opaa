package io.opaa.mail;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Builds and caches the {@link JavaMailSender} the current {@code mail_settings} describe (#1536,
 * ADR-0033 Entscheidung 10) and throws it away whenever those settings change - a corrected SMTP
 * host takes effect on the next message, <b>without a restart</b>.
 *
 * <p><b>{@link #current()} returns {@code null} when mail is not configured</b> ({@code enabled}
 * off or no host). That is not an error: a deployment without a mail server is supported, and
 * {@link MailService} turns it into {@code Skipped}.
 *
 * <p>Every connection is bounded by the {@link SmtpProperties} timeouts. Without them a stalled
 * SMTP peer holds the request thread of the person waiting for their invitation for as long as the
 * operating system's default keepalive allows - and the send path is deliberately synchronous
 * (ADR-0033: no outbox, no retry), so that thread is the caller's.
 */
@Component
public class MailSenderProvider {

  private static final Logger log = LoggerFactory.getLogger(MailSenderProvider.class);

  private static final String ENCODING = "UTF-8";

  private final MailSettingsService settingsService;
  private final SmtpProperties properties;
  private final AtomicReference<JavaMailSender> cached = new AtomicReference<>();

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
   * The cached sender, built on first use; {@code null} while mail is not configured. Two threads
   * racing on the first call may both build one - harmless, {@link JavaMailSenderImpl} holds no
   * connection until it sends, and only one of the two is kept.
   */
  public JavaMailSender current() {
    MailSettingsSnapshot snapshot = settingsService.snapshot();
    if (!snapshot.sendable()) {
      return null;
    }
    JavaMailSender existing = cached.get();
    if (existing != null) {
      return existing;
    }
    JavaMailSender built = build(snapshot);
    return cached.compareAndSet(null, built) ? built : cached.get();
  }

  /** Drops the cached sender; the next {@link #current()} builds from the new settings. */
  public void invalidate() {
    cached.set(null);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onSettingsChanged(MailSettingsChangedEvent event) {
    invalidate();
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
   * it must fail the connection rather than continue in clear, which is what "enabled" alone would
   * do.
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
              "SMTP-Verschluesselung ist auf NONE gestellt - die Verbindung zu {} ist unverschluesselt",
              snapshot.host());
    }
  }

  private static String millis(Duration duration) {
    return Long.toString(duration.toMillis());
  }
}
