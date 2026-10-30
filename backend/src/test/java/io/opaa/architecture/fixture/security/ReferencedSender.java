package io.opaa.architecture.fixture.security;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Sends through a method reference, which the invocations of the class alone do not show. */
public class ReferencedSender {

  /** The shape of {@link HttpClient#send}, so that it can be passed on as a reference. */
  public interface Sending {
    HttpResponse<String> send(HttpRequest request, HttpResponse.BodyHandler<String> handler)
        throws IOException, InterruptedException;
  }

  public Sending sender(HttpClient client) {
    return client::send;
  }

  public HttpRequest request(URI uri) {
    return HttpRequest.newBuilder(uri).build();
  }
}
