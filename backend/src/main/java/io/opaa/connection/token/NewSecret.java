package io.opaa.connection.token;

import java.time.Instant;
import java.util.Objects;

/**
 * A secret on its way into {@link ConnectionSecrets#store}; its form decides the stored kind. A
 * further form is a further permitted type. No form shows its value in a string.
 */
public sealed interface NewSecret permits NewSecret.Personal, NewSecret.OAuthGrant {

  /** An app password, a token or {@code user:password}, without an expiry of its own. */
  static NewSecret personal(String value) {
    return new Personal(value, null);
  }

  /**
   * A personal secret as entered.
   *
   * @param expiresAt when the provider said it ends, {@code null} for never
   */
  record Personal(String value, Instant expiresAt) implements NewSecret {

    public Personal {
      Objects.requireNonNull(value, "value");
      if (value.isBlank()) {
        throw new IllegalArgumentException("a personal secret is not blank");
      }
    }

    @Override
    public String toString() {
      return "Personal[value=***, expiresAt=" + expiresAt + "]";
    }
  }

  /**
   * The tokens of an OAuth consent: the refresh token is the stored secret, the access token is
   * held beside it until it expires.
   *
   * @param expiresAt when the refresh token ends as the provider said, {@code null} for unknown
   */
  record OAuthGrant(
      String refreshToken, String accessToken, Instant accessTokenExpiresAt, Instant expiresAt)
      implements NewSecret {

    public OAuthGrant {
      Objects.requireNonNull(refreshToken, "refreshToken");
      Objects.requireNonNull(accessToken, "accessToken");
      Objects.requireNonNull(accessTokenExpiresAt, "accessTokenExpiresAt");
      if (refreshToken.isBlank() || accessToken.isBlank()) {
        throw new IllegalArgumentException("an OAuth grant carries both tokens");
      }
    }

    @Override
    public String toString() {
      return "OAuthGrant[tokens=***, accessTokenExpiresAt="
          + accessTokenExpiresAt
          + ", expiresAt="
          + expiresAt
          + "]";
    }
  }
}
