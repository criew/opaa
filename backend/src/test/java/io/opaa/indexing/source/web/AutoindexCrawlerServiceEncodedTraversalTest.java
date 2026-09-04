package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers #1287 end to end against a real {@link HttpServer}: a directory page carrying an
 * ascend-by-percent-encoding link must never cause a request to the path that link resolves to once
 * decoded, so this asserts on the request count itself rather than just the parsed result.
 */
class AutoindexCrawlerServiceEncodedTraversalTest {

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, String html)
      throws IOException {
    byte[] body = html.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }

  @Test
  void aPercentEncodedParentLinkOnADirectoryPageIsNeverRequested()
      throws IOException, InterruptedException {
    AtomicInteger dokumenteRequests = new AtomicInteger();
    AtomicInteger internRequests = new AtomicInteger();
    server.createContext(
        "/dokumente/",
        exchange -> {
          dokumenteRequests.incrementAndGet();
          respond(
              exchange,
              """
              <table>
              <tr><td><img alt="[DIR]"></td><td><a href="%2E%2E/intern/">up</a></td>\
              <td>2025-01-01</td><td>-</td></tr>
              <tr><td><img alt="[TXT]"></td><td><a href="oeffentlich.txt">oeffentlich.txt</a></td>\
              <td>2025-01-01</td><td>1</td></tr>
              </table>
              """);
        });
    // A directory outside /dokumente/ the encoded link would resolve to - if the crawler ever
    // requests it, the traversal was not blocked.
    server.createContext(
        "/intern/",
        exchange -> {
          internRequests.incrementAndGet();
          respond(exchange, "<table></table>");
        });

    AutoindexCrawlerService service =
        new AutoindexCrawlerService(
            TargetAddressValidator.disabled(), new CrawlProperties(10, 100, 0));

    AutoindexCrawlerService.CrawlResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> service.crawl(baseUrl + "/dokumente/", null, -1, null, null, false));

    assertThat(result.entries())
        .extracting(AutoindexCrawlerService.CrawledFileEntry::name)
        .containsExactly("oeffentlich.txt");
    assertThat(dokumenteRequests.get()).isEqualTo(1);
    assertThat(internRequests.get())
        .as("the encoded parent-directory link must never be followed")
        .isZero();
  }
}
