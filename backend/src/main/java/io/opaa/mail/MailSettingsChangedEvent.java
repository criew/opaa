package io.opaa.mail;

/**
 * Published by {@link MailSettingsService} inside the transaction of every administrative change to
 * {@code mail_settings} (#1536, ADR-0033 Entscheidung 10).
 *
 * <p>{@link MailSettingsService} is the <b>only</b> listener, {@code AFTER_COMMIT}: it replaces the
 * snapshot, so a rolled-back change never takes effect. {@link MailSenderProvider} listens to
 * nothing - it binds its cached transport to the identity of the snapshot it was built from, which
 * is what keeps the rebuild order between the two from mattering at all (#1559 review, MEDIUM 7).
 *
 * <p>Deliberately not published for a status write ({@code last_success_at}/{@code
 * last_failure_at}): those change nothing the transport was built from, and rebuilding a working
 * SMTP connection after every sent mail would be a reconnect per message.
 */
public record MailSettingsChangedEvent() {}
