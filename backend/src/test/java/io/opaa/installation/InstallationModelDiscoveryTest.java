package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.account.PasswordPolicy;
import io.opaa.auth.OidcAddressPolicy;
import io.opaa.common.NotFoundException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.ValidationException;
import io.opaa.llm.LlmModelDiscoverer;
import io.opaa.llm.LlmModelRepository;
import io.opaa.llm.ModelAddressPolicy;
import io.opaa.llm.ModelDiscovery;
import io.opaa.security.PasswordEncoderConfiguration;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model discovery of the installation against a real local endpoint: it lists the models of a
 * model step's endpoint with the address check of models, reuses the key saved with the step only
 * for the same endpoint, sends no key when told so, refuses other steps and a completed
 * installation, and stops at its installation-wide budget.
 */
class InstallationModelDiscoveryTest {

  private static final String MODELS =
      "{\"object\":\"list\",\"data\":[{\"id\":\"qwen2.5:7b\"},{\"id\":\"bge-m3\"}]}";

  private final JsonMapper json = JsonMapper.builder().build();
  private final SettingsEncryptor encryptor =
      new SettingsEncryptor(
          new SettingsEncryptionProperties("c2V0dGluZ3NkZXZrZXkwMHNldHRpbmdzZGV2a2V5MDE="));
  private final ModelAddressPolicy policy = ModelAddressPolicy.allowing(List.of("127.0.0.1"));
  private final StepRules rules =
      new StepRules(
          new PasswordPolicy(() -> 12),
          new PasswordEncoderConfiguration().passwordEncoder(),
          encryptor,
          mock(OidcAddressPolicy.class),
          json,
          policy,
          InstallationTestMailTargets.LOOPBACK);
  private final InstallationStateRepository states = mock(InstallationStateRepository.class);
  private final InstallationStepRepository steps = mock(InstallationStepRepository.class);
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicInteger requests = new AtomicInteger();

  private HttpServer server;
  private String baseUrl;
  private InstallationModelDiscovery discovery;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/models",
        exchange -> {
          requests.incrementAndGet();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body = MODELS.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    LlmModelDiscoverer discoverer =
        new LlmModelDiscoverer(
            mock(LlmModelRepository.class), encryptor, JsonMapper.builder().build(), policy);
    discovery = new InstallationModelDiscovery(states, steps, rules, encryptor, discoverer);
    when(states.isCompleted()).thenReturn(false);
    when(steps.findById(InstallationStep.CHAT_MODEL)).thenReturn(Optional.empty());
    when(steps.findById(InstallationStep.EMBEDDING_MODEL)).thenReturn(Optional.empty());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void listsTheModelsOfTheEndpointWithTheGivenKey() {
    ModelDiscovery result = discovery.discover(InstallationStep.CHAT_MODEL, baseUrl, "sk-neu");

    assertThat(result.success()).isTrue();
    assertThat(result.models())
        .extracting(model -> model.identifier())
        .containsExactlyInAnyOrder("qwen2.5:7b", "bge-m3");
    assertThat(authorization.get()).isEqualTo("Bearer sk-neu");
  }

  @Test
  void theEmbeddingStepListsModelsAsWell() {
    ModelDiscovery result = discovery.discover(InstallationStep.EMBEDDING_MODEL, baseUrl, null);

    assertThat(result.success()).isTrue();
    assertThat(authorization.get()).isNull();
  }

  @Test
  void anOmittedKeyUsesTheKeySavedWithTheStepForTheSameEndpoint() {
    saveChatModel(baseUrl, "sk-gespeichert");

    discovery.discover(InstallationStep.CHAT_MODEL, baseUrl + "/", null);

    assertThat(authorization.get()).isEqualTo("Bearer sk-gespeichert");
  }

  // regression guard: the saved key never travels to an endpoint it was not saved for
  @Test
  void theSavedKeyIsRefusedForAnotherEndpointBeforeAnyRequest() {
    saveChatModel("https://api.openai.com/v1", "sk-gespeichert");

    assertThatThrownBy(() -> discovery.discover(InstallationStep.CHAT_MODEL, baseUrl, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Zugangsschlüssel")
        .hasMessageNotContaining("sk-gespeichert");
    assertThat(requests).hasValue(0);
  }

  @Test
  void anEmptyKeySendsNoneEvenWhenOneIsSaved() {
    saveChatModel(baseUrl, "sk-gespeichert");

    discovery.discover(InstallationStep.CHAT_MODEL, baseUrl, "");

    assertThat(requests).hasValue(1);
    assertThat(authorization.get()).isNull();
  }

  @Test
  void aRefusedAddressFailsWithoutAnyRequest() {
    ModelDiscovery result =
        discovery.discover(InstallationStep.CHAT_MODEL, "http://169.254.169.254/v1", "sk-neu");

    assertThat(result.success()).isFalse();
    assertThat(result.failure()).isEqualTo(ModelDiscovery.Failure.INVALID_ADDRESS);
    assertThat(result.message()).contains("OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST");
    assertThat(result.message()).doesNotContain("sk-neu");
  }

  @Test
  void anAddressWithCredentialsIsRefused() {
    assertThatThrownBy(
            () ->
                discovery.discover(
                    InstallationStep.CHAT_MODEL,
                    "http://nutzer:geheim@127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    null))
        .isInstanceOf(ValidationException.class)
        .hasMessageNotContaining("geheim");
    assertThat(requests).hasValue(0);
  }

  @Test
  void onlyTheModelStepsDiscover() {
    assertThatThrownBy(() -> discovery.discover(InstallationStep.FIRST_ADMIN, baseUrl, null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> discovery.discover(InstallationStep.SIGN_IN, baseUrl, null))
        .isInstanceOf(ValidationException.class);
    assertThat(requests).hasValue(0);
  }

  @Test
  void aCompletedInstallationAnswersNotFound() {
    when(states.isCompleted()).thenReturn(true);

    assertThatThrownBy(() -> discovery.discover(InstallationStep.CHAT_MODEL, baseUrl, null))
        .isInstanceOf(NotFoundException.class);
    assertThat(requests).hasValue(0);
  }

  @Test
  void theBudgetEndsFurtherDiscoveries() {
    for (int i = 0; i < InstallationModelDiscovery.MAX_DISCOVERIES; i++) {
      discovery.discover(InstallationStep.CHAT_MODEL, "http://169.254.169.254/v1", null);
    }

    // The code tells this budget apart from the per-address budget of the installation API.
    assertThatThrownBy(() -> discovery.discover(InstallationStep.CHAT_MODEL, baseUrl, null))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class,
            refused -> assertThat(refused.getCode()).isEqualTo("MODEL_DISCOVERY_LIMIT"));
    assertThat(requests).hasValue(0);
  }

  private void saveChatModel(String savedBaseUrl, String key) {
    InstallationStepRecord record = new InstallationStepRecord(InstallationStep.CHAT_MODEL);
    record.replaceValues(
        json.writeValueAsString(
            new StepRules.ChatModelPayload(savedBaseUrl, "qwen2.5:7b", null, null)),
        encryptor.encrypt(key),
        Instant.now());
    when(steps.findById(InstallationStep.CHAT_MODEL)).thenReturn(Optional.of(record));
  }
}
