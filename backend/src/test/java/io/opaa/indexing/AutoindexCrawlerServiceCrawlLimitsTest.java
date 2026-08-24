package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers #836: {@link AutoindexCrawlerService#crawl} must terminate on a cyclic directory structure
 * instead of recursing without bound - both the visited-URL guard (a genuine cycle back to an
 * already-crawled URL) and the depth limit (a same-origin cycle that never repeats a URL exactly,
 * e.g. a symlink loop growing the path by one segment per hop) are exercised against a stub {@link
 * HttpServer}.
 */
class AutoindexCrawlerServiceCrawlLimitsTest {

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

  private static String directoryListing(String dirHref, String dirName, String fileHref) {
    return """
        <table>
        <tr><td><img alt="[DIR]"></td><td><a href="%s">%s</a></td><td>2025-01-01</td><td>-</td></tr>
        <tr><td><img alt="[TXT]"></td><td><a href="%s">%s</a></td><td>2025-01-01</td><td>1</td></tr>
        </table>
        """
        .formatted(dirHref, dirName, fileHref, fileHref);
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
  void mutualCycleTerminatesAndVisitsEachDirectoryOnce() throws IOException, InterruptedException {
    // /a/ links (absolute, same-origin) to /b/, /b/ links back to /a/ - without a visited-URL
    // guard, crawlRecursive alternates between the two forever.
    AtomicInteger requestCount = new AtomicInteger();
    server.createContext(
        "/a/",
        exchange -> {
          requestCount.incrementAndGet();
          respond(exchange, directoryListing(baseUrl + "/b/", "b", "file-a.txt"));
        });
    server.createContext(
        "/b/",
        exchange -> {
          requestCount.incrementAndGet();
          respond(exchange, directoryListing(baseUrl + "/a/", "a", "file-b.txt"));
        });

    AutoindexCrawlerService service =
        new AutoindexCrawlerService(
            TargetAddressValidator.disabled(), new CrawlProperties(10, 100));

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> service.crawl(baseUrl + "/a/", null, -1, null, null, false));

    assertThat(entries)
        .extracting(AutoindexCrawlerService.CrawledFileEntry::name)
        .containsExactlyInAnyOrder("file-a.txt", "file-b.txt");
    // Exactly one fetch per directory - the second visit of either is skipped by the visited
    // guard rather than fetched (and recursed into) again.
    assertThat(requestCount.get()).isEqualTo(2);
  }

  @Test
  void everGrowingCycleIsBoundedByTheDepthLimit() throws IOException, InterruptedException {
    // Every path responds with a listing pointing one level deeper (relative "sub/") - a genuine
    // symlink-loop shape that never repeats the same URL, so the visited-URL guard alone would
    // never catch it; only the depth limit stops the recursion.
    AtomicInteger requestCount = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          requestCount.incrementAndGet();
          respond(exchange, directoryListing("sub/", "sub", "file.txt"));
        });

    int maxDepth = 3;
    AutoindexCrawlerService service =
        new AutoindexCrawlerService(
            TargetAddressValidator.disabled(), new CrawlProperties(maxDepth, 1000));

    List<AutoindexCrawlerService.CrawledFileEntry> entries =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> service.crawl(baseUrl + "/", null, -1, null, null, false));

    // One file discovered per visited directory level (0..maxDepth), then truncated.
    assertThat(entries).hasSize(maxDepth + 1);
    assertThat(requestCount.get()).isEqualTo(maxDepth + 1);
  }
}
