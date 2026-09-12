package io.opaa.mail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One template's effective content plus the delivered default alongside it (#1536), for the
 * administration API. A domain record, not the JPA entity: the effective content is often not a
 * stored row at all, and the delivered default has no row by definition.
 *
 * @param source where the effective content came from - a stored override or the delivered default
 * @param defaultBodyHtml the delivered fragment already inside the branded frame, placeholders
 *     intact, so "zurück zum Standard" can be shown before it is applied
 */
public record MailTemplateView(
    MailTemplateKey key,
    String locale,
    String subject,
    String bodyPlain,
    String bodyHtml,
    Source source,
    List<String> placeholders,
    String defaultSubject,
    String defaultBodyPlain,
    String defaultBodyHtml,
    Instant updatedAt,
    UUID updatedBy) {

  /** Where the effective content of a template comes from. */
  public enum Source {
    /** A stored {@code mail_templates} row an administrator saved. */
    DATABASE,
    /** The German text delivered with the release; no stored row exists. */
    DEFAULT
  }
}
