package io.opaa.mail;

/**
 * The outcome of a send attempt (#1536, ADR-0033 Entscheidung 10). {@link MailService#send} returns
 * one of these and <b>never throws</b>: mail is best-effort, and a caller that has just created an
 * account must be able to tell the person "das Konto steht, die Einladung ging nicht raus" instead
 * of failing the whole operation.
 */
public sealed interface SendResult {

  /** The message was handed to the SMTP server. */
  record Sent(String recipient) implements SendResult {}

  /**
   * No delivery was attempted because SMTP is not configured. A supported state, not a fault - the
   * caller falls back to showing the link (ADR-0033, Entscheidung 11).
   */
  record Skipped(String reason) implements SendResult {}

  /** Delivery was attempted and failed; {@code reason} carries the credential-free cause. */
  record Failed(String reason) implements SendResult {}

  /** Whether the message actually went out - the one question most callers have. */
  default boolean isSent() {
    return this instanceof Sent;
  }

  /** Why nothing was sent, or {@code null} for {@link Sent}. */
  default String reasonOrNull() {
    return switch (this) {
      case Sent ignored -> null;
      case Skipped skipped -> skipped.reason();
      case Failed failed -> failed.reason();
    };
  }
}
