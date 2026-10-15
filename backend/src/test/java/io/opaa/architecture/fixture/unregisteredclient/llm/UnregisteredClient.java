package io.opaa.architecture.fixture.unregisteredclient.llm;

import org.springframework.web.client.RestClient;

/** Builds a client to a model provider without registering the data flow. */
public class UnregisteredClient {

  public RestClient client() {
    return RestClient.create("https://models.example");
  }
}
