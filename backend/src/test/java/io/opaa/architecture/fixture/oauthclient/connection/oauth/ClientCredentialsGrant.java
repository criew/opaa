package io.opaa.architecture.fixture.oauthclient.connection.oauth;

import io.opaa.architecture.fixture.oauthclient.sourceaccess.SourceFormPost;

/** Posts to the token endpoint past the client. */
public class ClientCredentialsGrant {
  String token() {
    return SourceFormPost.post("token");
  }
}
