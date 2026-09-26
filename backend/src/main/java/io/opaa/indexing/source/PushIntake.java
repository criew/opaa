package io.opaa.indexing.source;

import io.opaa.api.types.DocumentSourceType;
import java.util.Objects;

/**
 * The push intake a connector offers, as its descriptor names it. Every intake authenticates with
 * the library's one push secret ({@code source_webhook_secret}); the connector decides the name the
 * audit gives that secret and how a library without the intake is told so.
 *
 * @param auditField the field name the audit records for a change of the secret - never its value
 * @param subject the German noun phrase for the secret, e.g. "Ein Webhook-Geheimnis"
 */
public record PushIntake(String auditField, String subject) {

  public PushIntake {
    Objects.requireNonNull(auditField, "auditField");
    Objects.requireNonNull(subject, "subject");
  }

  /** The German 400 message for a library whose type is not {@code ownerType}. */
  public String unavailableMessage(DocumentSourceType ownerType) {
    return subject + " gibt es nur für Bibliotheken vom Typ " + ownerType;
  }
}
