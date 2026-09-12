package io.opaa.api.types;

/**
 * How the SMTP connection of {@code mail_settings} is protected (ADR-0033, Entscheidung 10).
 *
 * <p>{@link #STARTTLS} upgrades a plaintext connection and is <em>required</em> - a server without
 * STARTTLS support is refused rather than silently downgraded; {@link #SSL} is implicit TLS from
 * the first byte; {@link #NONE} is unencrypted SMTP and only defensible for a relay on the same
 * trusted network.
 */
public enum MailEncryption {
  NONE,
  STARTTLS,
  SSL
}
