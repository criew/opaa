package io.opaa.connection.token;

import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SignInRejectedException;
import io.opaa.indexing.source.SourceCredentialsException;
import java.time.Instant;
import java.util.UUID;

/**
 * The port through which the token store reaches a provider for a token: renewal, revocation and a
 * profile's own sign-in need the profile's registration, which this package does not see.
 * Implemented above it ({@code connection.oauth}); without an implementation no OAuth token and no
 * token of a profile can be handed out.
 */
public interface SecretIssuer {

  /**
   * New tokens for {@code refreshToken} of the profile {@code profileId}, bound to {@code
   * issuedFor}; a rotated refresh token comes with them.
   *
   * @throws SignInRejectedException when the provider no longer takes the refresh token
   * @throws SourceCredentialsException when the provider cannot be reached or answers otherwise
   */
  Issued renew(UUID profileId, String refreshToken, String issuedFor);

  /**
   * What revokes {@code tokens} at the provider, with the registration of {@code profileId} as it
   * stands now, to run once after the discard committed; {@code null} where the provider offers no
   * revocation. It never throws.
   */
  Runnable revocation(UUID profileId, StoredTokens tokens);

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

  /**
   * Tokens a renewal obtained; {@link #toString} shows no value.
   *
   * @param refreshToken the rotated refresh token, {@code null} where the old one stays
   * @param refreshTokenExpiresAt when the rotated one ends, {@code null} for unknown
   */
  record Issued(
      String accessToken,
      Instant accessTokenExpiresAt,
      String refreshToken,
      Instant refreshTokenExpiresAt) {

    @Override
    public String toString() {
      return "Issued[accessToken=***, accessTokenExpiresAt="
          + accessTokenExpiresAt
          + ", rotated="
          + (refreshToken != null)
          + "]";
    }
  }

  /**
   * The tokens of a stored grant as they were before its discard; either may be {@code null}.
   * {@link #toString} shows no value.
   */
  record StoredTokens(String refreshToken, String accessToken) {

    @Override
    public String toString() {
      return "StoredTokens[***]";
    }
  }
}
