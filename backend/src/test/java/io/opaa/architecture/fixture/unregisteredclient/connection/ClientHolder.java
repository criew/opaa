package io.opaa.architecture.fixture.unregisteredclient.connection;

import java.net.http.HttpClient;

/** Only passes a client on without calling it: no use. */
public class ClientHolder {

  private final HttpClient client;

  public ClientHolder(HttpClient client) {
    this.client = client;
  }

  public HttpClient client() {
    return client;
  }
}
