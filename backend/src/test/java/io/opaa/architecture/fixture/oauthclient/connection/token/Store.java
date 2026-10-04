package io.opaa.architecture.fixture.oauthclient.connection.token;

/** The store keeps the refresh token. */
public class Store {
  String keep(NewSecret.OAuthGrant grant) {
    return grant.refreshToken();
  }
}
