package io.opaa.connection.token;

import io.opaa.indexing.credentials.SignInRejectedException;
import io.opaa.indexing.spi.Secret;
import io.opaa.indexing.spi.SourceCredentialsException;
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
   * The order that revokes {@code tokens} at the provider, with the registration of {@code
   * profileId} as it stands now: self-contained text that {@link #revoke} carries out on any
   * instance, once the discard committed and off any transaction. It holds the tokens and the
   * client secret and is stored only encrypted. {@code null} where the provider offers no
   * revocation; it never throws.
   */
  String revocation(UUID profileId, StoredTokens tokens);

  /**
   * Carries out an order of {@link #revocation} at its provider - never within a transaction. A
   * failure is logged without any token and never thrown; an unreadable order revokes nothing.
   */
  void revoke(String order);

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
   * The tokens of a stored grant as they were before its discard; any may be {@code null}. {@link
   * #toString} shows no value.
   *
   * @param accessTokenExpiresAt when the access token ends, {@code null} for unknown
   */
  record StoredTokens(String refreshToken, String accessToken, Instant accessTokenExpiresAt) {

    @Override
    public String toString() {
      return "StoredTokens[***]";
    }
  }
}
