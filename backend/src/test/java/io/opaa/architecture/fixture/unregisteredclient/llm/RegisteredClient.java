package io.opaa.architecture.fixture.unregisteredclient.llm;

import io.opaa.architecture.fixture.unregisteredclient.compliance.DataFlow;
import java.util.function.Supplier;
import org.springframework.web.client.RestClient;

/** Registered: its nested class and its lambda count as the annotated top-level class. */
@DataFlow("chat-model")
public class RegisteredClient {

  public RestClient client() {
    Supplier<RestClient> lazy = () -> RestClient.builder().build();
    return lazy.get();
  }

  static class Nested {
    RestClient client() {
      return RestClient.create();
    }
  }
}
