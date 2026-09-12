package io.opaa.mail;

import io.opaa.api.types.MailEncryption;
import org.springframework.util.StringUtils;

/**
 * The SMTP configuration in the form {@link MailSenderProvider} builds a transport from (#1536,
 * ADR-0033 Entscheidung 10): detached from the persistence context, with the password already
 * decrypted, so no send path opens a transaction or touches the encryption key.
 *
 * <p>{@link #toString()} is overridden to keep the password out of every log line, stack trace and
 * debugger label a record's generated {@code toString} would otherwise put it in.
 *
 * @param password the decrypted SMTP password, {@code null} when the connection is unauthenticated
 */
public record MailSettingsSnapshot(
    boolean enabled,
    String host,
    Integer port,
    String username,
    String password,
    MailEncryption encryption,
    String fromAddress,
    String fromName) {

  /**
   * Whether a send should be attempted at all: the master switch is on <em>and</em> a host is
   * configured. Either missing means {@link MailService} reports {@code Skipped} rather than
   * failing - a deployment without a mail server is a supported configuration, not a fault.
   */
  public boolean sendable() {
    return enabled && StringUtils.hasText(host);
  }

  /** Whether the transport authenticates; a blank username means an open relay on the inside. */
  public boolean authenticated() {
    return StringUtils.hasText(username);
  }

  @Override
  public String toString() {
    return "MailSettingsSnapshot[enabled="
        + enabled
        + ", host="
        + host
        + ", port="
        + port
        + ", username="
        + username
        + ", password="
        + (password == null ? "null" : "***")
        + ", encryption="
        + encryption
        + ", fromAddress="
        + fromAddress
        + ", fromName="
        + fromName
        + "]";
  }
}
