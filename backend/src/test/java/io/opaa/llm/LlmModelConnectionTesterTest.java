package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link LlmModelConnectionTester} against a real, local {@link HttpServer} - the same building
 * block {@code io.opaa.library.SourceConnectionTestServiceTest} uses for its own probes - so the
 * three distinguishable outcomes the issue's technical hint asks for (unreachable, unauthenticated,
 * unknown model) are proven against real HTTP responses, not mocked ones. Only DNS-failure and
 * connection-refused ("unreachable") are exercised via addresses this test does not control, since
 * standing up a genuinely unreachable server is otherwise not reproducible in CI.
 */
class LlmModelConnectionTesterTest {

  private HttpServer server;
  private String baseUrl;
  private LlmModelRepository repository;
  private SettingsEncryptor settingsEncryptor;
  private LlmModelConnectionTester tester;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";

    repository = mock(LlmModelRepository.class);
    settingsEncryptor =
        new SettingsEncryptor(
            new SettingsEncryptionProperties(
                Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes())));
    tester = new LlmModelConnectionTester(repository, settingsEncryptor);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void aSuccessfulProbeReportsSuccess() {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });

    LlmModelConnectionTester.TestOutcome outcome = tester.test(baseUrl, "phi3:mini", null, null);

    assertThat(outcome.success()).isTrue();
  }

  @Test
  void anUnauthorizedResponseIsDistinguishableFromUnreachable() {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });

    LlmModelConnectionTester.TestOutcome outcome = tester.test(baseUrl, "phi3:mini", null, null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("Authentifizierung").contains("401");
  }

  @Test
  void aNotFoundResponseIsDistinguishableAsAnUnknownModel() {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });

    LlmModelConnectionTester.TestOutcome outcome =
        tester.test(baseUrl, "does-not-exist", null, null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).contains("Modell-Kennung").contains("404");
  }

  @Test
  void anUnreachableAddressReportsFailureNotAnException() {
    server.stop(0);

    LlmModelConnectionTester.TestOutcome outcome = tester.test(baseUrl, "phi3:mini", null, null);

    assertThat(outcome.success()).isFalse();
    assertThat(outcome.message()).isNotBlank();
  }

  @Test
  void theAuthorizationHeaderCarriesTheGivenPlaintextKey() {
    AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });

    tester.test(baseUrl, "phi3:mini", "sk-plain-test-key", null);

    assertThat(receivedAuthHeader.get()).isEqualTo("Bearer sk-plain-test-key");
  }

  @Test
  void whenApiKeyIsOmittedTheStoredDecryptedKeyOfTheGivenModelIsUsedInstead() {
    UUID modelId = UUID.randomUUID();
    LlmModel model =
        new LlmModel(
            "Modell mit Schlüssel",
            baseUrl,
            "phi3:mini",
            new BigDecimal("0.70"),
            2000,
            settingsEncryptor.encrypt("sk-stored-secret"));
    when(repository.findById(modelId)).thenReturn(Optional.of(model));
    AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          receivedAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });

    tester.test(baseUrl, "phi3:mini", null, modelId);

    assertThat(receivedAuthHeader.get()).isEqualTo("Bearer sk-stored-secret");
  }
}
