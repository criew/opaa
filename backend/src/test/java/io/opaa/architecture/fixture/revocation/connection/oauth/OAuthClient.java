package io.opaa.architecture.fixture.revocation.connection.oauth;

/** The one client of the authorization server. */
public class OAuthClient {
  public boolean revoke(String token) {
    return token != null;
  }
}
