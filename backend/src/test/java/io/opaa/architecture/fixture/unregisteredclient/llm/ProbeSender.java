package io.opaa.architecture.fixture.unregisteredclient.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Sends through a client bean it did not build, without registering the data flow. */
public class ProbeSender {

  private final HttpClient client;

  public ProbeSender(HttpClient client) {
    this.client = client;
  }

  public int probe() throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://models.example")).build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }
}
