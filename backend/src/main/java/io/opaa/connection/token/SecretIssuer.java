package io.opaa.connection.token;

import io.opaa.indexing.source.Secret;
import java.util.UUID;

/**
 * The port through which the token store reaches a provider for an OAuth token: renewal and
 * revocation need the profile's registration, which this package does not see. Implemented above it
 * ({@code connection.oauth}); without an implementation no OAuth token can be handed out.
 */
public interface SecretIssuer {

  /**
   * An access token for {@code refreshToken} of the profile {@code profileId}, bound to {@code
   * issuedFor}.
   */
  Secret renew(UUID profileId, String refreshToken, String issuedFor);

  /**
   * What revokes {@code refreshToken} at the provider, to run once after the discard committed;
   * {@code null} where the provider offers no revocation.
   */
  Runnable revocation(UUID profileId, String refreshToken);
}
