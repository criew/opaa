package io.opaa.architecture.fixture.revocation.connection.oauth;

/** The completion revokes a fresh grant it could not store. */
public class ConnectionAuthorizationService {
  boolean complete(OAuthClient client) {
    return client.revoke("fresh");
  }
}
