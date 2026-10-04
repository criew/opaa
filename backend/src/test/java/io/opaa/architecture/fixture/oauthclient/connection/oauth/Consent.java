package io.opaa.architecture.fixture.oauthclient.connection.oauth;

/** The OAuth package hands the refresh token to the store. */
public class Consent {
  String store(OAuthClient.Grant grant) {
    return grant.refreshToken();
  }
}
