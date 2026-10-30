package io.opaa.architecture.fixture.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Sends to an address no building block checked. */
public class RawSender {

  public String fetch(URI uri) throws IOException, InterruptedException {
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client
          .send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString())
          .body();
    }
  }
}
