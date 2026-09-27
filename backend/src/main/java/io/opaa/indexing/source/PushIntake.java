package io.opaa.indexing.source;

import java.util.Objects;

/**
 * The push intake a connector offers, as its descriptor names it. Every intake authenticates with
 * the library's one push secret ({@code source_webhook_secret}); the connector decides the name the
 * audit gives that secret.
 *
 * @param auditField the field name the audit records for a change of the secret - never its value
 */
public record PushIntake(String auditField) {

  public PushIntake {
    Objects.requireNonNull(auditField, "auditField");
  }
}
