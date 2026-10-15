package io.opaa.architecture.fixture.unregisteredclient.indexing.source.web;

import io.opaa.architecture.fixture.unregisteredclient.sourceaccess.RedirectFollowingFetcher;
import java.net.http.HttpClient;

/** Sends through the carrier without registering the data flow. */
public class PageFetcher {

  public HttpClient client() {
    return RedirectFollowingFetcher.client();
  }
}
