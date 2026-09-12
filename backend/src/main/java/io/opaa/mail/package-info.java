/**
 * The mail subsystem (#1536, ADR-0033 Entscheidung 10): SMTP configuration an administrator changes
 * at runtime, a transport rebuilt without a restart, twelve German templates a deployment may
 * override and always reset, and a send that reports its outcome instead of raising.
 *
 * <p>{@link io.opaa.mail.MailService} is the single entry point for everything that sends; the
 * account lifecycle (#1537, #1538) and the notification channel of #1297 add variables and
 * templates, never a second send path.
 */
package io.opaa.mail;
