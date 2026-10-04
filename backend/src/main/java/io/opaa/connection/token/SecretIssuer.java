package io.opaa.connection.token;

import io.opaa.indexing.source.Secret;
import java.util.UUID;

/**
 * The port through which the token store reaches a provider for a token: renewal, revocation and a
 * profile's own sign-in need the profile's registration, which this package does not see.
 * Implemented above it ({@code connection.oauth}); without an implementation no OAuth token and no
 * token of a profile can be handed out.
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

  /**
   * An access token valid now from the own sign-in of the profile {@code profileId} - client
   * credentials or a service account key -, held in the process until shortly before it expires.
   *
   * @throws SecretRefusedException with {@code EXPIRED} after the provider rejected the
   *     registration, with {@code NOT_CONNECTED} without a secret or key; neither asks the provider
   */
  Secret mint(UUID profileId);

  /** Drops what the process holds for the profile's own sign-in; the next {@link #mint} asks. */
  void forgetMinted(UUID profileId);
}
