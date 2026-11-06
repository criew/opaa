package io.opaa.integration.docling;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.llm.TextRecognitionClient;
import io.opaa.llm.TextRecognitionClient.TestOutcome;
import io.opaa.llm.TextRecognitionEndpoint;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * The connection test of the text recognition role against a real docling-serve with a key: the
 * shipped test page is read with exactly the options of the operation (OCR forced, RapidOCR,
 * German, tables, JSON), a wrong key is told apart, and so is a service that is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class DoclingServeContainerTest {

  private static final Logger log = LoggerFactory.getLogger(DoclingServeContainerTest.class);

  /** The CPU image of docling-serve; the regex manager in {@code renovate.json5} reads it. */
  // renovate: datasource=docker depName=quay.io/docling-project/docling-serve-cpu
  static final String IMAGE = "quay.io/docling-project/docling-serve-cpu:v1.38.1";

  private static final String KEY = "opaa-ocr-it-schluessel";
  private static final int PORT = 5001;

  /** The first conversion loads layout, table and OCR models. */
  private static final Duration TEST_TIMEOUT = Duration.ofMinutes(5);

  private static GenericContainer<?> container;
  private static TextRecognitionClient client;

  @BeforeAll
  @SuppressWarnings("resource")
  static void start() {
    String image = System.getenv().getOrDefault("OPAA_OCR_IT_IMAGE", IMAGE);
    log.info("Starting docling-serve {}", image);
    container =
        new GenericContainer<>(DockerImageName.parse(image))
            .withEnv("DOCLING_SERVE_API_KEY", KEY)
            .withEnv("DOCLING_SERVE_ENABLE_UI", "false")
            .withEnv("DOCLING_SERVE_MAX_SYNC_WAIT", "600")
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/health").forPort(PORT).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(10));
    container.start();
    client = new TextRecognitionClient(JsonMapper.builder().build());
  }

  @AfterAll
  static void stop() {
    if (container != null) {
      container.stop();
    }
  }

  private static TextRecognitionEndpoint endpoint(String apiKey) {
    return new TextRecognitionEndpoint(
        true,
        TextRecognitionConnectionType.DOCLING_SERVE,
        "http://" + container.getHost() + ":" + container.getMappedPort(PORT),
        null,
        apiKey);
  }

  @Test
  void readsTheShippedTestPageWithTheOperationsOptions() {
    TextRecognitionClient.TestResult result = client.test(endpoint(KEY), TEST_TIMEOUT);

    assertThat(result.outcome()).as(result.diagnostic()).isEqualTo(TestOutcome.READY);
  }

  @Test
  void aWrongKeyIsToldApart() {
    assertThat(client.test(endpoint("falsch"), TEST_TIMEOUT).outcome())
        .isEqualTo(TestOutcome.KEY_REJECTED);
    assertThat(client.test(endpoint(""), TEST_TIMEOUT).outcome())
        .isEqualTo(TestOutcome.KEY_REJECTED);
  }

  @Test
  void theProbeAnswersWithoutConvertingAnything() {
    assertThat(client.probe(endpoint(KEY), Duration.ofSeconds(30))).isNull();
  }

  @Test
  void aServiceThatIsNotThereIsUnreachable() {
    TextRecognitionEndpoint gone =
        new TextRecognitionEndpoint(
            true, TextRecognitionConnectionType.DOCLING_SERVE, "http://127.0.0.1:9", null, KEY);

    assertThat(client.test(gone, Duration.ofSeconds(30)).outcome())
        .isEqualTo(TestOutcome.UNREACHABLE);
  }
}
