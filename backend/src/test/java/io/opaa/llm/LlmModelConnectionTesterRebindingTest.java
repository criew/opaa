package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpServer;
import io.opaa.security.ScriptedResolution;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * DNS rebinding against the connection test of a model endpoint (ADR-0063): the model host answers
 * the address check of models with a public address and the probe with loopback, where an internal
 * service listens. The probe must reach only the vetted address.
 */
class LlmModelConnectionTesterRebindingTest {

  private static final String HOST = "model.rebind.opaa.test";

  private HttpServer internal;
  private final AtomicInteger requests = new AtomicInteger();
  private ScriptedResolution dns;

  @BeforeEach
  void setUp() throws IOException {
    internal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    internal.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          byte[] body =
              "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    internal.start();
    dns = ScriptedResolution.install().rebinding(HOST, "2001:db8::443", "127.0.0.1");
  }

  @AfterEach
  void tearDown() {
    dns.close();
    internal.stop(0);
  }

  @Test
  void theConnectionTestReachesOnlyTheAddressTheCheckVetted() {
    LlmModelConnectionTester tester =
        new LlmModelConnectionTester(
            mock(LlmModelRepository.class),
            new SettingsEncryptor(
                new SettingsEncryptionProperties(
                    Base64.getEncoder()
                        .encodeToString(
                            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)))),
            new ObjectMapper(),
            ModelAddressPolicy.allowing(List.of()));

    LlmModelConnectionTester.TestOutcome outcome =
        tester.test(
            "http://" + HOST + ":" + internal.getAddress().getPort() + "/v1",
            "modell",
            "schluessel",
            null,
            null);

    assertThat(requests.get())
        .as("requests the internal service on 127.0.0.1 received after the check")
        .isZero();
    assertThat(outcome.success()).isFalse();
  }
}
