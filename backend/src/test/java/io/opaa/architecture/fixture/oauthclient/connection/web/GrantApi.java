package io.opaa.architecture.fixture.oauthclient.connection.web;

import io.opaa.architecture.fixture.oauthclient.connection.oauth.OAuthClient;

/** The web layer answers with a grant; its access token alone would pass. */
public abstract class GrantApi {
  String show(OAuthClient.Grant grant) {
    return grant.accessToken() + grant.refreshToken();
  }
}
