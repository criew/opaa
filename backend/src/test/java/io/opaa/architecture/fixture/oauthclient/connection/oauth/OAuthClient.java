package io.opaa.architecture.fixture.oauthclient.connection.oauth;

import io.opaa.architecture.fixture.oauthclient.sourceaccess.SourceFormPost;

/** The one client of the authorization server. */
public class OAuthClient {
  String exchange() {
    return SourceFormPost.post("token");
  }

  /** The tokens an answer carries. */
  public record Grant(String refreshToken, String accessToken) {}
}
