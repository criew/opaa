package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;

class UrlFileDownloaderTest {

  // Target validation is exercised on its own dedicated stand (TargetAddressValidatorTest) -
  // disabled here since every server this class talks to is deliberately loopback.
  private final UrlFileDownloader downloader =
      new UrlFileDownloader(TargetAddressValidator.disabled());

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
                      null,
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
            null,
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
            httpClient, baseUrl + "/anlage.pdf", "anlage.pdf", 10_000, "OPAA-Indexer/test", null);

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
            httpClient, baseUrl + "/anlage.pdf", "anlage.pdf", 10_000, "OPAA-Indexer/test", null);

    Files.deleteIfExists(result.path());
    assertThat(userAgent.get()).isEqualTo("OPAA-Indexer/test");
  }

  @Test
  void downloadBoundedSendsTheGivenAuthorizationHeader() throws IOException, InterruptedException {
    // #505: RssFeedIndexingExecutor now applies a library's own sourceCredentials to attachment
    // downloads too, mirroring download()'s existing authHeader parameter.
    AtomicReference<String> authorization = new AtomicReference<>();
    server.createContext(
        "/anlage.pdf",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    UrlFileDownloader.DownloadedFile result =
        downloader.downloadBounded(
            httpClient, baseUrl + "/anlage.pdf", "anlage.pdf", 10_000, null, "Basic dGVzdDp0ZXN0");

    Files.deleteIfExists(result.path());
    assertThat(authorization.get()).isEqualTo("Basic dGVzdDp0ZXN0");
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
            () ->
                downloader.downloadBounded(
                    httpClient, baseUrl + "/big.pdf", "big.pdf", 10, null, null))
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
                    httpClient, baseUrl + "/missing.pdf", "missing.pdf", 10_000, null, null))
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
                      httpClient, baseUrl + "/anlage.pdf", "anlage.pdf", 10_000, null, null))
          .isInstanceOf(UrlFileDownloader.ForeignHostRedirectException.class);
    } finally {
      foreignServer.stop(0);
    }
  }

  @Test
  void downloadBoundedThrowsWhenRedirectedToAHostUriCannotParse() throws IOException {
    // #651: a redirect target with a host java.net.URI cannot parse (e.g. one containing an
    // underscore, per RFC an illegal reg-name character) makes URI#getHost() return null on that
    // side - isForeignHostRedirect previously special-cased "either host null" as "not foreign" and
    // let the header-stripping/rejection logic treat this exactly like a same-origin redirect,
    // the opposite of AutoindexCrawlerService.sameOrigin's own null-host handling (#615 review,
    // finding 1: "both hosts null must not compare equal"). A redirect target OPAA cannot even
    // identify the host of must never be treated as trustworthy.
    //
    // Uses the production client (Redirect.NEVER, downloadBounded's own manual redirect loop,
    // mirroring downloadBoundedThrowsWhenRedirectedToAForeignHostWithTheProductionClient above) -
    // the underscore host is never actually resolvable, so a NORMAL client auto-following the
    // redirect at the JDK level would fail with an UnknownHostException before ever reaching
    // isForeignHostRedirect, unlike downloadBounded's own proactive check on the raw 3xx response.
    server.createContext(
        "/anlage.pdf",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "http://ex_ample.invalid/anlage.pdf");
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
                    null,
                    null))
        .isInstanceOf(UrlFileDownloader.ForeignHostRedirectException.class);
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

  // --- #404 review, finding 1: downloadPrefix never reads more than the requested cap ----------

  @Test
  void downloadPrefixNeverReturnsMoreThanMaxBytesEvenWhenTheResponseIsLarger() throws Exception {
    // The core BLOCKER fix: UrlIndexingExecutor decides from this bounded sample alone, before
    // #download's own unbounded, full transfer ever runs - a multi-gigabyte file behind a listing
    // must not have to be written to disk in full just to be rejected.
    byte[] body = "x".repeat(5_000).getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/big.iso",
        exchange -> {
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    byte[] prefix = downloader.downloadPrefix(httpClient, null, baseUrl + "/big.iso", 100);

    assertThat(prefix).hasSize(100);
  }

  @Test
  void downloadPrefixReturnsTheWholeBodyWhenItIsSmallerThanMaxBytes()
      throws IOException, InterruptedException {
    server.createContext(
        "/anlage.pdf",
        exchange -> {
          byte[] bytes = "%PDF-1.4 not real content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    byte[] prefix = downloader.downloadPrefix(httpClient, null, baseUrl + "/anlage.pdf", 10_000);

    assertThat(new String(prefix, StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4 not real content");
  }

  @Test
  void downloadPrefixThrowsOnNon200Status() {
    server.createContext(
        "/missing.pdf",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });

    assertThatThrownBy(
            () -> downloader.downloadPrefix(httpClient, null, baseUrl + "/missing.pdf", 10_000))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("HTTP 404");
  }

  @Test
  void downloadPrefixSendsTheGivenAuthorizationHeader() throws IOException, InterruptedException {
    AtomicReference<String> authorization = new AtomicReference<>();
    server.createContext(
        "/anlage.pdf",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    downloader.downloadPrefix(httpClient, "Basic dGVzdDp0ZXN0", baseUrl + "/anlage.pdf", 10_000);

    assertThat(authorization.get()).isEqualTo("Basic dGVzdDp0ZXN0");
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
                    httpClient, "https://example.com/anlage.pdf", "anlage.pdf", 10_000, null, null))
        .isInstanceOf(UrlFileDownloader.ForeignHostRedirectException.class)
        .hasMessageContaining("protocol downgrade");
  }

  @Test
  void downloadBoundedFollowsASameHostHttpToHttpsUpgradeRedirectAndResendsAuthorization()
      throws IOException, InterruptedException {
    // #693: a same-host http->https upgrade at matching (here: both default) ports is not a
    // foreign origin - before the fix, this was rejected outright with
    // ForeignHostRedirectException, exactly like a genuine cross-origin redirect, breaking every
    // Basic-Auth-protected http:// source whose server upgrades every request to https (as every
    // well-behaved one does). Mocked at the HttpClient level (mirrors the protocol-downgrade test
    // above) since neither example.com nor a real TLS listener is reachable from this test.
    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> redirectResponse = mock(HttpResponse.class);
    when(redirectResponse.statusCode()).thenReturn(301);
    when(redirectResponse.uri()).thenReturn(URI.create("http://example.com/anlage.pdf"));
    when(redirectResponse.headers())
        .thenReturn(
            HttpHeaders.of(
                Map.of("Location", List.of("https://example.com/anlage.pdf")), (a, b) -> true));
    when(redirectResponse.body()).thenReturn(InputStream.nullInputStream());

    @SuppressWarnings("unchecked")
    HttpResponse<InputStream> finalResponse = mock(HttpResponse.class);
    when(finalResponse.statusCode()).thenReturn(200);
    when(finalResponse.uri()).thenReturn(URI.create("https://example.com/anlage.pdf"));
    when(finalResponse.headers())
        .thenReturn(
            HttpHeaders.of(Map.of("Content-Type", List.of("application/pdf")), (a, b) -> true));
    when(finalResponse.body())
        .thenReturn(new ByteArrayInputStream("Inhalt".getBytes(StandardCharsets.UTF_8)));

    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(redirectResponse, finalResponse);

    UrlFileDownloader.DownloadedFile result =
        downloader.downloadBounded(
            httpClient,
            "http://example.com/anlage.pdf",
            "anlage.pdf",
            10_000,
            null,
            "Basic geheim");

    assertThat(result.contentType()).isEqualTo("application/pdf");
    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(2)).send(requestCaptor.capture(), any());
    HttpRequest secondRequest = requestCaptor.getAllValues().get(1);
    assertThat(secondRequest.uri()).isEqualTo(URI.create("https://example.com/anlage.pdf"));
    // Zugangsdaten-Verhalten (#693 Soll-Zustand): same host, more secure channel - the stored
    // credential is resent, not dropped as it would be for a genuine cross-origin redirect.
    assertThat(secondRequest.headers().firstValue("Authorization")).contains("Basic geheim");
  }

  @Test
  void downloadRejectsARedirectToABlockedTargetWhenValidationIsEnabled()
      throws IOException, InterruptedException {
    // PR #699 review, finding 2 (#267 acceptance criterion: "Die Prüfung greift auch, wenn erst
    // eine Weiterleitung auf ein solches Ziel führt"). Deliberately exercises download() (backed
    // by AutoindexCrawlerService.sendFollowingRedirects), not downloadBounded(): the latter's own
    // foreign-host check (isForeignHostRedirect) already rejects any cross-origin redirect outright
    // - the very case this test needs - before the per-hop validate() call underneath it is ever
    // reached, which would make the test pass without actually exercising the SSRF check.
    // sendFollowingRedirects has no such origin restriction (it only conditionally drops
    // Authorization, see its own Javadoc), so its per-hop validate() call is the only thing
    // rejecting this redirect.
    //
    // The start host (127.0.0.1, itself loopback) is allowlisted so this test isolates the
    // redirect-hop check - without allowlisting it, the very first validate() call would already
    // reject the start URL, and the test would pass for the wrong reason even if the hop-level
    // check were accidentally removed.
    TargetAddressValidator enabledValidator =
        new TargetAddressValidator(
            new IndexingProperties.TargetValidation(true, List.of("127.0.0.1")));
    UrlFileDownloader validatingDownloader = new UrlFileDownloader(enabledValidator);
    server.createContext(
        "/anlage.pdf",
        exchange -> {
          // A different loopback address than the allowlisted one - not itself allowlisted.
          exchange.getResponseHeaders().set("Location", "http://127.0.0.2:1/anlage.pdf");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });

    assertThatThrownBy(
            () ->
                validatingDownloader.download(
                    AutoindexCrawlerService.buildHttpClient(null, -1, false),
                    null,
                    baseUrl + "/anlage.pdf",
                    "anlage.pdf"))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class)
        .hasMessageContaining("gesperrten Adressbereich");
  }
}
