package io.opaa.architecture.fixture.oauthclient.connection.account;

import io.opaa.architecture.fixture.oauthclient.connection.token.NewSecret;

/** The account package reads a refresh token. */
public class Leak {
  String remember(NewSecret.OAuthGrant grant) {
    return grant.refreshToken();
  }
}
