/**
 * The mail subsystem (#1536, ADR-0033 Entscheidung 10): SMTP configuration an administrator changes
 * at runtime, a transport rebuilt without a restart, twelve German templates that a deployment may
 * override and always reset, and a send that reports its outcome instead of raising.
 *
 * <p>{@link io.opaa.mail.MailService} is the single entry point for everything that sends. It is
 * deliberately independent of the flows that use it - account lifecycle (#1537, #1538) and the
 * notification channel of #1297 add variables and templates, never a second send path.
 *
 * <p>{@link io.opaa.mail.MailSettingsService} owns the {@code mail_settings} singleton and the
 * process-local snapshot; {@link io.opaa.mail.MailSenderProvider} turns that snapshot into a {@code
 * JavaMailSender} and throws it away on every committed change. {@link
 * io.opaa.mail.MailTemplateKey} is the closed registry, {@link io.opaa.mail.MailTemplateService}
 * resolves database before default and renders strictly, and {@link
 * io.opaa.mail.EmailLayoutBuilder} puts the operator's branding around every HTML part.
 *
 * <p>{@code io.opaa.api.SystemMailSettingsController} and {@code
 * io.opaa.api.SystemMailTemplateController} expose all of it to {@code SystemRole.SYSTEM_ADMIN}
 * alone.
 */
package io.opaa.mail;
