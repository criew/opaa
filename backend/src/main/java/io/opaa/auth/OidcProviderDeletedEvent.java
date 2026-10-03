package io.opaa.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * Published inside the transaction that deletes an identity provider. A provider with the same
 * issuer created later makes its accounts usable again; whatever must not survive the deletion ends
 * with this event instead.
 */
public record OidcProviderDeletedEvent(String issuerUri, UUID actorUserId) {

  public OidcProviderDeletedEvent {
    Objects.requireNonNull(issuerUri, "issuerUri");
    Objects.requireNonNull(actorUserId, "actorUserId");
  }
}
