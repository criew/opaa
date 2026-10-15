package io.opaa.architecture.fixture.unregisteredclient.sourceaccess;

import io.opaa.architecture.fixture.unregisteredclient.compliance.DataFlowCarrier;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** A carrier: builds the client, the caller names the target. */
@DataFlowCarrier
public final class RedirectFollowingFetcher {

  public static final Duration TIMEOUT = Duration.ofSeconds(5);

  public RedirectFollowingFetcher() {}

  public static HttpClient client() {
    return HttpClient.newHttpClient();
  }

  public static boolean sameOrigin(URI first, URI second) {
    return first.getHost().equals(second.getHost());
  }
}
