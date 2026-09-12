package io.opaa.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One administrator override of a delivered mail template, for one key and one locale (#1536,
 * ADR-0033 Entscheidung 10). The table holds overrides only: which templates exist is {@link
 * MailTemplateKey}, so deleting this row restores the delivered German text rather than leaving a
 * template without content.
 */
@Entity
@Table(name = "mail_templates")
public class MailTemplate {

  @Id private UUID id;

  @Column(name = "template_key", length = 60, nullable = false)
  private String templateKey;

  @Column(name = "locale", length = 10, nullable = false)
  private String locale;

  @Column(name = "subject", length = 300, nullable = false)
  private String subject;

  @Column(name = "body_plain", nullable = false)
  private String bodyPlain;

  @Column(name = "body_html")
  private String bodyHtml;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  protected MailTemplate() {}

  MailTemplate(MailTemplateKey key, String locale) {
    this.id = UUID.randomUUID();
    this.templateKey = key.key();
    this.locale = locale;
  }

  public UUID getId() {
    return id;
  }

  public String getTemplateKey() {
    return templateKey;
  }

  public String getLocale() {
    return locale;
  }

  public String getSubject() {
    return subject;
  }

  public String getBodyPlain() {
    return bodyPlain;
  }

  public String getBodyHtml() {
    return bodyHtml;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }

  /** Replaces the stored content; a blank HTML body is stored as {@code null} ("use the frame"). */
  void replaceContent(String subject, String bodyPlain, String bodyHtml, UUID actorUserId) {
    this.subject = subject;
    this.bodyPlain = bodyPlain;
    this.bodyHtml = bodyHtml == null || bodyHtml.isBlank() ? null : bodyHtml;
    this.updatedBy = actorUserId;
    this.updatedAt = Instant.now();
  }
}
