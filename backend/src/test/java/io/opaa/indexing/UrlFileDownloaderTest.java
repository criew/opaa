package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    // NORMAL, not the default NEVER (#492 review, finding 4's test needs an actually-followed
    // redirect to exercise isForeignHostRedirect) - mirrors
    // AutoindexCrawlerService.buildHttpClient,
    // the client production code actually uses for every RSS attachment download.
    httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void preservesFileExtension() throws IOException, InterruptedException {
    @SuppressWarnings("unchecked")
    HttpResponse<Path> response = mock(HttpResponse.class);
    HttpClient httpClient = mock(HttpClient.class);

    Path tempFile = Files.createTempFile("opaa-", ".pdf");
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(tempFile);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenAnswer(
            inv -> {
              // The handler creates the temp file, we return our mock response
              return response;
            });

    try {
      Path result =
          downloader.download(
              httpClient, null, "https://example.com/files/report.pdf", "report.pdf");
      assertThat(result.getFileName().toString()).endsWith(".pdf");
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  void throwsOnNon200Status() throws IOException, InterruptedException {
    @SuppressWarnings("unchecked")
    HttpResponse<Path> response = mock(HttpResponse.class);
    HttpClient httpClient = mock(HttpClient.class);

    Path tempFile = Files.createTempFile("opaa-", ".txt");
    when(response.statusCode()).thenReturn(404);
    when(response.body()).thenReturn(tempFile);
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
    // 127.0.0.2, not 127.0.0.1 (also loopback, but a genuinely different host string) - two
    // servers on 127.0.0.1 at different ports would make isForeignHostRedirect's host-only
    // comparison see the same host and miss the redirect entirely.
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
                      httpClient, baseUrl + "/anlage.pdf", "anlage.pdf", 10_000, null))
          .isInstanceOf(UrlFileDownloader.ForeignHostRedirectException.class);
    } finally {
      foreignServer.stop(0);
    }
  }
}
