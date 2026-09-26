package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;

/**
 * The kinds of push intake a connector may offer. Each kind authenticates with the library's one
 * push secret ({@code source_webhook_secret}); the kind decides the name the audit gives that
 * secret and how a library without the intake is told so.
 */
public enum PushIntake {
  /** A webhook signed or accompanied by the library's secret. */
  WEBHOOK_SECRET("confluenceWebhookSecret", "Ein Webhook-Geheimnis"),
  /** Event notifications presenting the library's token. */
  EVENT_TOKEN("s3EventsToken", "Ein Ereignis-Token");

  private final String auditField;
  private final String subject;

  PushIntake(String auditField, String subject) {
    this.auditField = auditField;
    this.subject = subject;
  }

  /** The field name the audit records for a change of the secret - never its value. */
  public String auditField() {
    return auditField;
  }

  /** The German 400 message for a library whose type is not {@code ownerType}. */
  public String unavailableMessage(DocumentSourceType ownerType) {
    return subject + " gibt es nur für Bibliotheken vom Typ " + ownerType;
  }
}
