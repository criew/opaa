package io.opaa.architecture.fixture.oauthclient.library;

import io.opaa.architecture.fixture.oauthclient.sourceaccess.SourceFormPost;

/** Outside connections a form post is not this rule's. */
public class Fetcher {
  String fetch() {
    return SourceFormPost.post("elsewhere");
  }
}
