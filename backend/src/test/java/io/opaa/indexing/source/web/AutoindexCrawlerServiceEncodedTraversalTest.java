package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers #1287/#1300 end to end against a real {@link HttpServer}: a directory page carrying a link
 * that only resolves outside the start URL once percent-decoded must never cause a request for the
 * path it decodes to - for both the HTMLTable and the link-based layout, and for both a relative
 * and an already-absolute href. {@code com.sun.net.httpserver.HttpServer} itself routes purely on
 * the raw (still-encoded) request path prefix, so a plain {@code /intern/} context would never be
 * hit by a request the crawler sends as {@code /dokumente/%2E%2E/intern/} - {@link
 * #decodedNormalizedPath} decodes and normalizes every request the single {@code /dokumente/}
 * context actually receives, so the assertion is on the real target the request addresses, not on
 * which context happened to answer it.
 */
class AutoindexCrawlerServiceEncodedTraversalTest {

  private static final String BASE_PATH = "/dokumente/";

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

  /**
   * Decodes every percent-escape in {@code rawPath} and then collapses {@code .}/{@code ..}
   * segments - in that order, since a segment like {@code %2E%2E} only becomes a literal {@code ..}
   * ripe for collapsing once decoded. Mirrors what a real autoindex server does before resolving
   * the path, independently of {@code AutoindexCrawlerService}'s own decode-and-check so this test
   * does not simply assert its own production logic back at itself.
   */
  private static String decodedNormalizedPath(String rawPath) {
    String decoded = URI.create("http://placeholder" + rawPath).getPath();
    return URI.create("http://placeholder" + decoded).normalize().getPath();
  }

  @Test
  void aPercentEncodedParentLinkOnADirectoryPageIsNeverRequested()
      throws IOException, InterruptedException {
    AtomicInteger dokumenteRequests = new AtomicInteger();
    AtomicInteger escapedRequests = new AtomicInteger();
    server.createContext(
        BASE_PATH,
        exchange -> {
          String rawPath = exchange.getRequestURI().getRawPath();
          if (!decodedNormalizedPath(rawPath).startsWith(BASE_PATH)) {
            escapedRequests.incrementAndGet();
            respond(exchange, "<table></table>");
            return;
          }
          dokumenteRequests.incrementAndGet();
          if (rawPath.equals(BASE_PATH)) {
            respond(
                exchange,
                """
                <table>
                <tr><td><img alt="[DIR]"></td><td><a href="%2E%2E/intern/">up</a></td>\
                <td>2025-01-01</td><td>-</td></tr>
                <tr><td><img alt="[TXT]"></td>\
                <td><a href="oeffentlich.txt">oeffentlich.txt</a></td>\
                <td>2025-01-01</td><td>1</td></tr>
                </table>
                """);
          } else {
            respond(exchange, "Oeffentlicher Inhalt.");
          }
        });

    AutoindexCrawlerService service =
        new AutoindexCrawlerService(
            TargetAddressValidator.disabled(), new CrawlProperties(10, 100, 0));

    AutoindexCrawlerService.CrawlResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> service.crawl(baseUrl + BASE_PATH, null, -1, null, null, false));

    assertThat(result.entries())
        .extracting(AutoindexCrawlerService.CrawledFileEntry::name)
        .containsExactly("oeffentlich.txt");
    assertThat(dokumenteRequests.get()).isEqualTo(1);
    assertThat(escapedRequests.get())
        .as("no request may ever decode-and-normalize to a path outside " + BASE_PATH)
        .isZero();
  }

  @Test
  void anAbsoluteSameOriginLinkOutsideBaseUrlInTheHtmlTableLayoutIsNeverRequested()
      throws IOException, InterruptedException {
    // An already-absolute, same-origin link pointing outside baseUrl's own subtree must never be
    // followed in the HTMLTable layout either - the same rule a relative href is held to.
    AtomicInteger dokumenteRequests = new AtomicInteger();
    AtomicInteger escapedRequests = new AtomicInteger();
    server.createContext(
        BASE_PATH,
        exchange -> {
          String rawPath = exchange.getRequestURI().getRawPath();
          if (!decodedNormalizedPath(rawPath).startsWith(BASE_PATH)) {
            escapedRequests.incrementAndGet();
            respond(exchange, "<table></table>");
            return;
          }
          dokumenteRequests.incrementAndGet();
          if (rawPath.equals(BASE_PATH)) {
            respond(
                exchange,
                """
                <table>
                <tr><td><img alt="[DIR]"></td>\
                <td><a href="%s/intern/">intern</a></td><td>2025-01-01</td><td>-</td></tr>
                <tr><td><img alt="[TXT]"></td>\
                <td><a href="oeffentlich.txt">oeffentlich.txt</a></td>\
                <td>2025-01-01</td><td>1</td></tr>
                </table>
                """
                    .formatted(baseUrl));
          } else {
            respond(exchange, "Oeffentlicher Inhalt.");
          }
        });
    server.createContext(
        "/intern/",
        exchange -> {
          escapedRequests.incrementAndGet();
          respond(exchange, "<table></table>");
        });

    AutoindexCrawlerService service =
        new AutoindexCrawlerService(
            TargetAddressValidator.disabled(), new CrawlProperties(10, 100, 0));

    AutoindexCrawlerService.CrawlResult result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> service.crawl(baseUrl + BASE_PATH, null, -1, null, null, false));

    assertThat(result.entries())
        .extracting(AutoindexCrawlerService.CrawledFileEntry::name)
        .containsExactly("oeffentlich.txt");
    assertThat(dokumenteRequests.get()).isEqualTo(1);
    assertThat(escapedRequests.get())
        .as("the absolute, same-origin link outside baseUrl must never be followed")
        .isZero();
  }
}
