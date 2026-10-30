package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opaa.indexing.spi.RemoteContentProperties;
import io.opaa.indexing.spi.RemoteOriginalAccess;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.test.ProductionDocumentFormats;
import io.opaa.test.SourceTypes;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The original of a directory document carries the source's credentials only inside the start URL's
 * origin and subtree - the scope the crawl applies ({@link
 * AutoindexCrawlerService#credentialScope}).
 */
class HttpDirectoryOriginalCredentialsTest {

  private static final String CREDENTIALS = "diruser:dirpass";
  private static final String EXPECTED_HEADER =
      "Basic " + Base64.getEncoder().encodeToString(CREDENTIALS.getBytes(StandardCharsets.UTF_8));

  private final List<String> receivedInside = new CopyOnWriteArrayList<>();
  private final List<String> receivedOutside = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private String base;
  private HttpDirectorySourceConnector connector;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/akten/bericht.pdf", exchange -> answer(exchange, receivedInside));
    server.createContext("/andere/geheim.pdf", exchange -> answer(exchange, receivedOutside));
    server.createContext(
        "/akten/umzug.pdf",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/andere/geheim.pdf");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    TargetAddressValidator validator = TargetAddressValidator.disabled();
    connector =
        new HttpDirectorySourceConnector(
            new AutoindexCrawlerService(validator),
            validator,
            SourceRequestPolicy.defaults(),
            ProductionDocumentFormats.supportedFormats(),
            1024 * 1024,
            new RemoteOriginalAccess(
                new BoundedDownloader(validator), validator, new RemoteContentProperties(0, 0, 0)));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void anOriginalInsideTheStartSubtreeGetsTheCredentials() throws IOException {
    readOriginal(base + "/akten/bericht.pdf");

    assertThat(receivedInside).containsExactly(EXPECTED_HEADER);
  }

  // regression guard for #3338: same origin, but outside the start URL's subtree
  @Test
  void anOriginalOutsideTheStartSubtreeIsFetchedWithoutTheCredentials() throws IOException {
    readOriginal(base + "/andere/geheim.pdf");

    assertThat(receivedOutside).containsExactly("<none>");
  }

  @Test
  void aRedirectOutOfTheStartSubtreeDropsTheCredentials() throws IOException {
    readOriginal(base + "/akten/umzug.pdf");

    assertThat(receivedOutside).containsExactly("<none>");
  }

  private void readOriginal(String documentUrl) throws IOException {
    Document document =
        new Document(
            "dokument.pdf", documentUrl, "application/pdf", null, SourceTypes.HTTP_DIRECTORY);
    Optional<DocumentContent> content =
        connector.openOriginal(
            document,
            KnowledgeLibrary.ownedByUser(
                UUID.randomUUID(),
                "Akten",
                null,
                UUID.randomUUID(),
                SourceTypes.HTTP_DIRECTORY,
                null,
                base + "/akten",
                CREDENTIALS),
            new SourceSettings(null, base + "/akten", CREDENTIALS, null));
    assertThat(content).isPresent();
    try (InputStream stream = content.get().stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("inhalt");
    }
  }

  private static void answer(HttpExchange exchange, List<String> receivedAuthorization)
      throws IOException {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    receivedAuthorization.add(header == null ? "<none>" : header);
    byte[] body = "inhalt".getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }
}
