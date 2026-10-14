/**
 * The mail subsystem (ADR-0033 Entscheidung 10): SMTP configuration an administrator changes at
 * runtime, a transport rebuilt without a restart, German templates a deployment may override and
 * always reset, and a send that reports its outcome instead of raising.
 *
 * <p>{@link io.opaa.mail.MailService} is the single entry point for everything that sends; the
 * account lifecycle and the notification channel of #1297 add variables and templates, never a
 * second send path.
 */
package io.opaa.mail;
