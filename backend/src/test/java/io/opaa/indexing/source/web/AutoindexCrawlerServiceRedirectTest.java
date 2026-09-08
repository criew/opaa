package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Server-side redirects during {@link AutoindexCrawlerService#crawl}: the source configuration's
 * {@code Authorization} travels only to targets below the crawl's start URL (regression guard for
 * #1301 - a same-origin redirect to a sibling path used to keep the credentials), and a listing
 * reached through a redirect inside the subtree is parsed against the URL it was actually served
 * from.
 */
class AutoindexCrawlerServiceRedirectTest {

  private static final String AUTH = "Basic YWRtaW46c2VjcmV0";

  private HttpServer server;
  private String origin;

  /** Request path to the {@code Authorization} header it arrived with ({@code "-"} for none). */
  private final Map<String, String> authorizationByPath = new ConcurrentHashMap<>();

  /** Request path to how often it was requested. */
  private final Map<String, Integer> requestsByPath = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    origin = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private static String listing(String... hrefs) {
    StringBuilder html = new StringBuilder("<table>\n");
    for (String href : hrefs) {
      String icon = href.endsWith("/") ? "[DIR]" : "[TXT]";
      html.append("<tr><td><img alt=\"")
          .append(icon)
          .append("\"></td><td><a href=\"")
          .append(href)
          .append("\">")
          .append(href)
          .append("</a></td><td>2025-01-01</td><td>1</td></tr>\n");
    }
    return html.append("</table>\n").toString();
  }

  /** Serves {@code hrefs} as a listing at exactly {@code path}; anything below it is a 404. */
  private void serveListing(String path, String... hrefs) {
    server.createContext(
        path,
        exchange -> {
          record(exchange);
          if (!path.equals(exchange.getRequestURI().getRawPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
          }
          byte[] body = listing(hrefs).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
  }

  private void redirect(String path, String location) {
    server.createContext(
        path,
        exchange -> {
          record(exchange);
          exchange.getResponseHeaders().set("Location", location);
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
  }

  private void record(com.sun.net.httpserver.HttpExchange exchange) {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    authorizationByPath.put(exchange.getRequestURI().getRawPath(), auth == null ? "-" : auth);
    requestsByPath.merge(exchange.getRequestURI().getRawPath(), 1, Integer::sum);
  }

  private AutoindexCrawlerService.CrawlResult crawl(String startUrl)
      throws IOException, InterruptedException {
    AutoindexCrawlerService service =
        new AutoindexCrawlerService(
            TargetAddressValidator.disabled(), new CrawlProperties(10, 100, 0));
    return service.crawl(startUrl, null, -1, "admin", "secret", false);
  }

  @Test
  void aSameOriginRedirectOutOfTheStartSubtreeDropsTheCredentials()
      throws IOException, InterruptedException {
    serveListing("/dokumente/", "unterordner/");
    redirect("/dokumente/unterordner/", origin + "/intern/");
    serveListing("/intern/", "geheim.txt");

    AutoindexCrawlerService.CrawlResult result = crawl(origin + "/dokumente/");

    assertThat(authorizationByPath.get("/dokumente/")).isEqualTo(AUTH);
    assertThat(authorizationByPath.get("/dokumente/unterordner/")).isEqualTo(AUTH);
    assertThat(authorizationByPath.get("/intern/")).isEqualTo("-");
    // the page outside is never parsed, and its subtree is content the crawl did not see
    assertThat(result.entries()).isEmpty();
    assertThat(result.incomplete()).isTrue();
    assertThat(result.redirectedOutside())
        .containsExactly(
            new AutoindexCrawlerService.RedirectedOutside(
                origin + "/dokumente/unterordner/", origin));
  }

  @Test
  void aPageRedirectedOutsideIsReportedByItsTargetOriginOnly()
      throws IOException, InterruptedException {
    serveListing("/dokumente/", "unterordner/");
    redirect("/dokumente/unterordner/", origin + "/login?SAMLRequest=geheim");

    AutoindexCrawlerService.CrawlResult result = crawl(origin + "/dokumente/");

    assertThat(result.redirectedOutside()).hasSize(1);
    assertThat(result.redirectedOutside().get(0).targetOrigin())
        .isEqualTo(origin)
        .doesNotContain("login", "geheim");
    assertThat(result.rejectedLinks()).isEmpty();
  }

  @Test
  void aRedirectEscapingTheSubtreeOnlyAfterPercentDecodingDropsTheCredentials()
      throws IOException, InterruptedException {
    serveListing("/dokumente/", "unterordner/");
    redirect("/dokumente/unterordner/", origin + "/dokumente/%2E%2E/intern/");

    AutoindexCrawlerService.CrawlResult result = crawl(origin + "/dokumente/");

    assertThat(authorizationByPath)
        .as("the encoded traversal target was requested, but without credentials")
        .containsEntry("/dokumente/%2E%2E/intern/", "-");
    assertThat(result.incomplete()).isTrue();
  }

  @Test
  void aRedirectInsideTheSubtreeKeepsTheCredentialsAndResolvesLinksAgainstTheTarget()
      throws IOException, InterruptedException {
    serveListing("/dokumente/", "alt/");
    redirect("/dokumente/alt/", origin + "/dokumente/neu/");
    serveListing("/dokumente/neu/", "datei.txt");

    AutoindexCrawlerService.CrawlResult result = crawl(origin + "/dokumente/");

    assertThat(authorizationByPath.get("/dokumente/neu/")).isEqualTo(AUTH);
    assertThat(result.entries())
        .extracting(AutoindexCrawlerService.CrawledFileEntry::url)
        .containsExactly(origin + "/dokumente/neu/datei.txt");
    assertThat(result.incomplete()).isFalse();
  }

  @Test
  void aRedirectTargetInsideTheSubtreeCountsAsVisited() throws IOException, InterruptedException {
    serveListing("/dokumente/", "alt/", "neu/");
    redirect("/dokumente/alt/", origin + "/dokumente/neu/");
    serveListing("/dokumente/neu/", "datei.txt");

    AutoindexCrawlerService.CrawlResult result = crawl(origin + "/dokumente/");

    assertThat(requestsByPath.get("/dokumente/neu/")).isEqualTo(1);
    assertThat(result.entries())
        .extracting(AutoindexCrawlerService.CrawledFileEntry::url)
        .containsExactly(origin + "/dokumente/neu/datei.txt");
  }

  @Test
  void theCredentialScopeCoversTheStartUrlItselfAndItsSubtreeOnly() {
    var scope = AutoindexCrawlerService.credentialScope("http://host.example/liste.php");

    assertThat(scope.test(URI.create("http://host.example/liste.php?dir=a"))).isTrue();
    assertThat(scope.test(URI.create("http://host.example/liste.php/unten/"))).isTrue();
    assertThat(scope.test(URI.create("http://host.example/liste.phpx"))).isFalse();
    assertThat(scope.test(URI.create("http://host.example/"))).isFalse();
  }
}
