package io.opaa.architecture.fixture.unregisteredclient.connection;

import io.opaa.architecture.fixture.unregisteredclient.sourceaccess.RedirectFollowingFetcher;
import java.net.URI;
import java.time.Duration;

/** Builds the carrier, reads its constant and calls a listed helper: none of it opens anything. */
public class OriginCheck {

  public Object carrier() {
    return new RedirectFollowingFetcher();
  }

  public Duration timeout() {
    return RedirectFollowingFetcher.TIMEOUT;
  }

  public boolean same(URI first, URI second) {
    return RedirectFollowingFetcher.sameOrigin(first, second);
  }
}
