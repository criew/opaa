package io.opaa.mail;

/**
 * Published by {@link MailSettingsService} inside the transaction of every administrative change to
 * {@code mail_settings} (#1536, ADR-0033 Entscheidung 10). {@link MailSenderProvider} and {@link
 * MailSettingsService} itself listen {@code AFTER_COMMIT}, so a rolled-back change never rebuilds
 * the transport from state that was never durable - the same contract {@code
 * OidcProvidersChangedEvent} has with {@code OidcProviderRegistry}.
 *
 * <p>Deliberately not published for a status write ({@code last_success_at}/{@code
 * last_failure_at}): those change nothing the transport was built from, and rebuilding a working
 * SMTP connection after every sent mail would be a reconnect per message.
 */
public record MailSettingsChangedEvent() {}
