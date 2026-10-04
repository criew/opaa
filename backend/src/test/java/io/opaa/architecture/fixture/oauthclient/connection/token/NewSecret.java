package io.opaa.architecture.fixture.oauthclient.connection.token;

/** A secret on its way into the store. */
public sealed interface NewSecret permits NewSecret.OAuthGrant {
  /** The tokens of a consent. */
  record OAuthGrant(String refreshToken, String accessToken) implements NewSecret {}
}
