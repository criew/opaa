package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UrlFileDownloaderTest {

  private final UrlFileDownloader downloader = new UrlFileDownloader();

  private HttpServer server;
  private String baseUrl;
  private HttpClient httpClient;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    // NORMAL, not the JDK's own default NEVER (#492 review, finding 4's test needs an
    // actually-followed redirect to exercise the post-hoc isForeignHostRedirect check
    // downloadBounded
    // still carries as a safety net). Production itself now builds its client with Redirect.NEVER
    // (#538, AutoindexCrawlerService.buildHttpClient) and has downloadBounded follow redirects
    // manually instead - downloadBoundedThrowsWhenRedirectedToAForeignHost below exercises the
    // post-hoc path with this NORMAL client on purpose, since a NEVER client never reaches it.
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void preservesFileExtension() throws IOException, InterruptedException {
    // #538: download() now streams the response body itself (via
    // AutoindexCrawlerService.sendFollowingRedirects, HttpResponse<InputStream>) instead of handing
    // the client a HttpResponse.BodyHandlers.ofFile(...) target directly - the mock therefore
    // returns an InputStream, not a pre-written Path.
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    HttpClient httpClient = mock(HttpClient.class);

    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    Path result =
        downloader.download(httpClient, null, "https://example.com/files/report.pdf", "report.pdf");
    try {
      assertThat(result.getFileName().toString()).endsWith(".pdf");
    } finally {
      Files.deleteIfExists(result);
    }
  }

  @Test
  void throwsOnNon200Status() throws IOException, InterruptedException {
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    HttpClient httpClient = mock(HttpClient.class);

    when(response.statusCode()).thenReturn(404);
    when(response.body()).thenReturn(InputStream.nullInputStream());
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    assertThatThrownBy(
            () ->
                downloader.download(
                    httpClient, null, "https://example.com/missing.txt", "missing.txt"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("HTTP 404");
  }

  @Test
  void downloadDoesNotLeakAuthorizationToAForeignHostRedirect()
      throws IOException, InterruptedException {
    // #538 reproduction: the production client from AutoindexCrawlerService.buildHttpClient (not
    // this test's own NORMAL client above, which mirrors the JDK's own leaking behaviour) must not
    // replay Authorization to a redirect target on a different host than baseUrl.
    HttpServer foreignServer = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
    foreignServer.start();
    String foreignBaseUrl = "http://127.0.0.2:" + foreignServer.getAddress().getPort();
    AtomicReference<String> receivedAuthorization = new AtomicReference<>("(never contacted)");
    try {
      foreignServer.createContext(
          "/report.pdf",
          exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "fremd".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.createContext(
          "/report.pdf",
          exchange -> {
            exchange.getResponseHeaders().set("Location", foreignBaseUrl + "/report.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });

      Path result =
          downloader.download(
              AutoindexCrawlerService.buildHttpClient(null, -1, false),
              "Basic dGVzdDp0ZXN0",
              baseUrl + "/report.pdf",
              "report.pdf");
      try {
        assertThat(receivedAuthorization.get()).isNull();
      } finally {
        Files.deleteIfExists(result);
      }
    } finally {
      foreignServer.stop(0);
    }
  }

  @Test
  void downloadForwardsAuthorizationOnASameHostRedirect() throws IOException, InterruptedException {
    // Legitimate same-host redirects (e.g. a trailing-slash normalisation) must keep working with
    // the production client, Authorization included - only a foreign-host hop drops it.
    AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    server.createContext(
        "/report.pdf",
        exchange -> {
          exchange.getResponseHeaders().set("Location", baseUrl + "/final.pdf");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/final.pdf",
        exchange -> {
          receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    Path result =
        downloader.download(
            AutoindexCrawlerService.buildHttpClient(null, -1, false),
            "Basic dGVzdDp0ZXN0",
            baseUrl + "/report.pdf",
            "report.pdf");
    try {
      assertThat(receivedAuthorization.get()).isEqualTo("Basic dGVzdDp0ZXN0");
    } finally {
      Files.deleteIfExists(result);
    }
  }

  @Test
  void downloadBoundedThrowsWhenRedirectedToAForeignHostWithTheProductionClient()
      throws IOException {
    // #538: exercises the proactive redirect loop downloadBounded now needs of its own, since the
    // production client (AutoindexCrawlerService.buildHttpClient) no longer auto-follows and never
    // reaches the pre-existing post-hoc isForeignHostRedirect check the test above exercises.
    HttpServer foreignServer = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
    foreignServer.start();
    String foreignBaseUrl = "http://127.0.0.2:" + foreignServer.getAddress().getPort();
    try {
      foreignServer.createContext(
          "/anlage.pdf",
          exchange -> {
            byte[] bytes = "fremd".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.createContext(
          "/anlage.pdf",
          exchange -> {
            exchange.getResponseHeaders().set("Location", foreignBaseUrl + "/anlage.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });

      assertThatThrownBy(
              () ->
                  downloader.downloadBounded(
                      AutoindexCrawlerService.buildHttpClient(null, -1, false),
                      baseUrl + "/anlage.pdf",
                      "anlage.pdf",
                      10_000,
                      null))
          .isInstanceOf(UrlFileDownloader.ForeignHostRedirectException.class);
    } finally {
      foreignServer.stop(0);
    }
  }

  @Test
  void downloadBoundedFollowsASameHostRedirectWithTheProductionClient()
      throws IOException, InterruptedException {
    server.createContext(
        "/anlage.pdf",
        exchange -> {
          exchange.getResponseHeaders().set("Location", baseUrl + "/final.pdf");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/final.pdf",
        exchange -> {
          byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/pdf");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    UrlFileDownloader.DownloadedFile result =
        downloader.downloadBounded(
            AutoindexCrawlerService.buildHttpClient(null, -1, false),
            baseUrl + "/anlage.pdf",
            "anlage.pdf",
            10_000,
            null);
    try {
      assertThat(Files.readString(result.path())).isEqualTo("content");
    } finally {
      Files.deleteIfExists(result.path());
    }
  }

  @Test
  void downloadBoundedReturnsFileAndDeclaredContentType() throws IOException, InterruptedException {
    server.createContext(
        "/anlage.pdf",
        exchange -> {
          byte[] bytes = "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/pdf");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    UrlFileDownloader.DownloadedFile result =
        downloader.downloadBounded(
            httpClient, baseUrl + "/anlage.pdf", "anlage.pdf", 10_000, "OPAA-Indexer/test");

    try {
      assertThat(result.contentType()).isEqualTo("application/pdf");
      assertThat(Files.readString(result.path())).isEqualTo("%PDF-1.4 not real content");
    } finally {
      Files.deleteIfExists(result.path());
    }
  }

  @Test
  void downloadBoundedSendsTheGivenUserAgent() throws IOException, InterruptedException {
    AtomicReference<String> userAgent = new AtomicReference<>();
    server.createContext(
        "/anlage.pdf",
        exchange -> {
          userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
          byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    UrlFileDownloader.DownloadedFile result =
        downloader.downloadBounded(
            httpClient, baseUrl + "/anlage.pdf", "anlage.pdf", 10_000, "OPAA-Indexer/test");

    Files.deleteIfExists(result.path());
    assertThat(userAgent.get()).isEqualTo("OPAA-Indexer/test");
  }

  @Test
  void downloadBoundedThrowsWhenTheResponseExceedsTheLimit() {
    server.createContext(
        "/big.pdf",
        exchange -> {
          byte[] bytes = "x".repeat(500).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    assertThatThrownBy(
            () -> downloader.downloadBounded(httpClient, baseUrl + "/big.pdf", "big.pdf", 10, null))
        .isInstanceOf(UrlFileDownloader.AttachmentTooLargeException.class);
  }

  @Test
  void downloadBoundedThrowsOnNon200Status() {
    server.createContext(
        "/missing.pdf",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });

    assertThatThrownBy(
            () ->
                downloader.downloadBounded(
                    httpClient, baseUrl + "/missing.pdf", "missing.pdf", 10_000, null))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("HTTP 404");
  }

  @Test
  void downloadBoundedThrowsWhenRedirectedToAForeignHost() throws IOException {
    // Same host (127.0.0.1), two different ports - not a second host string like 127.0.0.2 (#538
    // follow-up review, finding 1): isForeignHostRedirect originally compared hosts only, so two
    // servers on the same host at different ports would have looked identical to it and missed
    // the redirect entirely. isForeignHostRedirect now delegates to
    // AutoindexCrawlerService.sameOrigin, which normalizes and compares the port too - this test
    // exercises exactly that gap instead of sidestepping it.
    HttpServer foreignServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    foreignServer.start();
    String foreignBaseUrl = "http://127.0.0.1:" + foreignServer.getAddress().getPort();
    try {
      foreignServer.createContext(
          "/anlage.pdf",
          exchange -> {
            byte[] bytes = "fremd".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.createContext(
          "/anlage.pdf",
          exchange -> {
            exchange.getResponseHeaders().set("Location", foreignBaseUrl + "/anlage.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });

      assertThatThrownBy(
              () ->
                  downloader.downloadBounded(
                      httpClient, baseUrl + "/anlage.pdf", "anlage.pdf", 10_000, null))
          .isInstanceOf(UrlFileDownloader.ForeignHostRedirectException.class);
    } finally {
      foreignServer.stop(0);
    }
  }

  @Test
  void downloadDropsAuthorizationOnASameHostDifferentPortRedirect()
      throws IOException, InterruptedException {
    // #538 follow-up review, finding 1: sendFollowingRedirects originally compared host+scheme
    // only, so a redirect to a different port of the same host would have kept Authorization
    // attached - a service on a different port is a different origin, exactly what
    // AutoindexCrawlerService.sameOrigin (scheme+host+normalized port) now catches.
    HttpServer otherPortServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    otherPortServer.start();
    String otherPortBaseUrl = "http://127.0.0.1:" + otherPortServer.getAddress().getPort();
    AtomicReference<String> receivedAuthorization = new AtomicReference<>("(never contacted)");
    try {
      otherPortServer.createContext(
          "/report.pdf",
          exchange -> {
            receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
          });
      server.createContext(
          "/report.pdf",
          exchange -> {
            exchange.getResponseHeaders().set("Location", otherPortBaseUrl + "/report.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
          });

      Path result =
          downloader.download(
              AutoindexCrawlerService.buildHttpClient(null, -1, false),
              "Basic dGVzdDp0ZXN0",
              baseUrl + "/report.pdf",
              "report.pdf");
      try {
        assertThat(receivedAuthorization.get()).isNull();
      } finally {
        Files.deleteIfExists(result);
      }
    } finally {
      otherPortServer.stop(0);
    }
  }

  @Test
  void downloadRejectsAProtocolDowngradeRedirect() throws IOException, InterruptedException {
    // #538 follow-up review, finding 2: Redirect.NORMAL always refused to follow a redirect from
    // https to http; none of the manual replacement loops originally checked for that. Mocked
    // (like preservesFileExtension above) rather than a real HttpServer - the test HttpServer stub
    // used elsewhere in this class only ever speaks plain http, so it cannot itself answer an
    // https request to demonstrate the downgrade being refused.
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    HttpClient httpClient = mock(HttpClient.class);

    when(response.statusCode()).thenReturn(302);
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of("Location", List.of("http://example.com/report.pdf")), (a, b) -> true));
    when(response.body()).thenReturn(InputStream.nullInputStream());
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    assertThatThrownBy(
            () ->
                downloader.download(
                    httpClient, null, "https://example.com/report.pdf", "report.pdf"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("protocol downgrade");
  }

  @Test
  void downloadBoundedRejectsAProtocolDowngradeRedirect() throws IOException, InterruptedException {
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    HttpClient httpClient = mock(HttpClient.class);

    when(response.statusCode()).thenReturn(302);
    when(response.uri()).thenReturn(URI.create("https://example.com/anlage.pdf"));
    when(response.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of("Location", List.of("http://example.com/anlage.pdf")), (a, b) -> true));
    when(response.body()).thenReturn(InputStream.nullInputStream());
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(response);

    assertThatThrownBy(
            () ->
                downloader.downloadBounded(
                    httpClient, "https://example.com/anlage.pdf", "anlage.pdf", 10_000, null))
        .isInstanceOf(UrlFileDownloader.ForeignHostRedirectException.class)
        .hasMessageContaining("protocol downgrade");
  }
}
