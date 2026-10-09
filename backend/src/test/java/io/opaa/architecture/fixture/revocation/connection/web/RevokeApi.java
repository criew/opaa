package io.opaa.architecture.fixture.revocation.connection.web;

import io.opaa.architecture.fixture.revocation.connection.oauth.OAuthClient;

/** Revokes on the request thread. */
public class RevokeApi {
  boolean revoke(OAuthClient client) {
    return client.revoke("t");
  }
}
