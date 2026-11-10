package io.opaa.integration.docling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.format.file.pdf.PdfOcrDocumentFormat;
import io.opaa.format.file.pdf.PdfPages;
import io.opaa.format.shared.HeadingSectionSplitterFixture;
import io.opaa.format.shared.ParserLimitProperties;
import io.opaa.indexing.document.DocumentTextRecognitionStore;
import io.opaa.indexing.document.ScannedDocumentRecognition;
import io.opaa.indexing.document.TextRecognitionIndexing;
import io.opaa.llm.textrecognition.TextRecognitionClient;
import io.opaa.llm.textrecognition.TextRecognitionClient.TestOutcome;
import io.opaa.llm.textrecognition.TextRecognitionEndpoint;
import io.opaa.llm.textrecognition.TextRecognitionModelRole;
import io.opaa.llm.textrecognition.TextRecognitionModelRole.ActiveTextRecognition;
import io.opaa.llm.textrecognition.TextRecognitionProperties;
import io.opaa.observability.IndexingMetrics;
import io.opaa.sourceaccess.LoggedName;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * The connection test of the text recognition role against a real docling-serve with a key: the
 * shipped test page is read with exactly the options of the operation (OCR forced, RapidOCR,
 * German, tables, JSON), a wrong key is told apart, and so is a service that is not reachable. The
 * indexing reads the same scan into chunks with frames.
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

  /** The scan without a text layer OPAA ships for the connection test. */
  private static final String TEST_PAGE = "/text-recognition/test-page.pdf";

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

  /**
   * The acceptance of the indexing: a scan without a text layer goes through {@link
   * TextRecognitionIndexing} to the real service and comes back as chunks of {@code pdf-ocr} with
   * frames on the page and the note that the text was recognised.
   */
  @Test
  void aScanIsRecognisedIntoChunksWithFramesAndTextOrigin(@TempDir Path dir) throws IOException {
    Path scan = dir.resolve("scan.pdf");
    try (InputStream in = TextRecognitionClient.class.getResourceAsStream(TEST_PAGE)) {
      Files.copy(in, scan);
    }
    ActiveTextRecognition active = new ActiveTextRecognition(endpoint(KEY), "cfg-it");
    TextRecognitionModelRole role = mock(TextRecognitionModelRole.class);
    when(role.active()).thenReturn(Optional.of(active));
    when(role.limits()).thenReturn(new TextRecognitionProperties(true, 0, null, 0, 0, null, null));
    when(role.convertWithDocling(any(), any(), any()))
        .thenAnswer(
            call ->
                client.convertWithDocling(
                    endpoint(KEY), shorter(call.getArgument(2)), call.getArgument(1)));
    DocumentTextRecognitionStore store = mock(DocumentTextRecognitionStore.class);
    UUID documentId = UUID.randomUUID();
    when(store.find(documentId)).thenReturn(Optional.empty());
    TextRecognitionIndexing indexing =
        new TextRecognitionIndexing(
            role,
            store,
            new PdfOcrDocumentFormat(HeadingSectionSplitterFixture.DEFAULT),
            new PdfPages(new ParserLimitProperties(0, 0, 0, 0, 0, 0, 0, 0, 0)),
            new IndexingMetrics(new SimpleMeterRegistry()),
            Clock.systemUTC());

    Optional<ScannedDocumentRecognition.Recognized> recognized =
        indexing.recognize(
            new ScannedDocumentRecognition.Candidate(
                documentId, "c".repeat(64), scan, "scan.pdf", LoggedName.OPEN, null, false));

    assertThat(recognized).as("the test page is recognised").isPresent();
    DocumentFormatResult result = recognized.get().result();
    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    assertThat(recognized.get().pipeline().id()).isEqualTo(PdfOcrDocumentFormat.ID);
    assertThat(result.chunks()).isNotEmpty();
    for (Document chunk : result.chunks()) {
      assertThat(chunk.getText()).isNotBlank();
      assertThat(chunk.getMetadata())
          .containsEntry(
              ChunkMetadataKeys.TEXT_RECOGNITION_METADATA_KEY,
              java.util.Map.of("connectionType", "DOCLING_SERVE", "configurationId", "cfg-it"));
      ChunkAnchor anchor = ChunkAnchors.read(chunk.getMetadata());
      assertThat(anchor.textOrigin()).isEqualTo(new ChunkAnchor.TextOrigin("DOCLING_SERVE"));
      assertThat(anchor.pageFrom()).isEqualTo(1);
      assertThat(anchor.regions())
          .isNotEmpty()
          .allSatisfy(
              region -> {
                assertThat(region.page()).isEqualTo(1);
                assertThat(region.x()).isBetween(0.0, 1.0);
                assertThat(region.y()).isBetween(0.0, 1.0);
                assertThat(region.width()).isPositive().isLessThanOrEqualTo(1.0 - region.x());
                assertThat(region.height()).isPositive().isLessThanOrEqualTo(1.0 - region.y());
              });
    }
  }

  private static Duration shorter(Duration timeout) {
    return timeout.compareTo(TEST_TIMEOUT) < 0 ? timeout : TEST_TIMEOUT;
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
  void theProbeRefusesAWrongKeySoTheRoleIsNotReady() {
    TextRecognitionClient.ProbeFailure failure =
        client.probe(endpoint("falsch"), Duration.ofSeconds(30));

    assertThat(failure).isNotNull();
    assertThat(failure.keyRejected()).isTrue();
    assertThat(client.probe(endpoint(""), Duration.ofSeconds(30)))
        .extracting(TextRecognitionClient.ProbeFailure::keyRejected)
        .isEqualTo(true);
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
