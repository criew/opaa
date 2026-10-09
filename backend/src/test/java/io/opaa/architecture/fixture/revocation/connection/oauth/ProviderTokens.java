package io.opaa.architecture.fixture.revocation.connection.oauth;

import io.opaa.architecture.fixture.revocation.connection.token.SecretIssuer;

/** The port's implementation revokes through the client. */
public class ProviderTokens implements SecretIssuer {
  private final OAuthClient client = new OAuthClient();

  @Override
  public Runnable revocation(String token) {
    return () -> client.revoke(token);
  }
}
