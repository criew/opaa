package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.dto.SourceConnectionTestRequest;
import io.opaa.api.dto.SourceConnectionTestResponse;
import io.opaa.indexing.AutoindexCrawlerService;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.RssFeedParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit-level coverage of {@link SourceConnectionTestService} for all three testable quellentypen
 * (#514) - real building blocks throughout ({@link DocumentService}, {@link
 * AutoindexCrawlerService}, {@link RssFeedParser}), only {@link FilesystemPathAllowlist} is mocked
 * so the FILESYSTEM branch can be exercised without depending on operator configuration.
 */
class SourceConnectionTestServiceTest {

  private HttpServer server;
  private String baseUrl;
  private FilesystemPathAllowlist filesystemAllowlist;
  private SourceConnectionTestService service;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    service =
        new SourceConnectionTestService(
            new DocumentService(),
            new AutoindexCrawlerService(),
            new RssFeedParser(),
            filesystemAllowlist,
            new IndexingProperties(null, 1000, 0, 50, 3, null, null, null));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  // --- FILESYSTEM ---------------------------------------------------------

  @Test
  void filesystemReportsSupportedDocumentCount(@TempDir Path dir) throws IOException {
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed(dir.toString())).thenReturn(true);
    Files.writeString(dir.resolve("a.txt"), "hello");
    Files.writeString(dir.resolve("b.pdf"), "pdf-ish");
    Files.writeString(dir.resolve("c.xyz"), "unsupported");

    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.FILESYSTEM)
                .sourcePath(dir.toString()));

    assertThat(response.getReachable()).isTrue();
    assertThat(response.getDocumentCount()).isEqualTo(2L);
    assertThat(response.getMessage()).contains("2").contains("Dokumente");
  }

  @Test
  void filesystemReportsUnreachableForMissingDirectory(@TempDir Path dir) {
    String missing = dir.resolve("does-not-exist").toString();
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed(missing)).thenReturn(true);

    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.FILESYSTEM)
                .sourcePath(missing));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getDocumentCount()).isNull();
    assertThat(response.getMessage()).isEqualTo("Das Verzeichnis existiert nicht.");
  }

  @Test
  void filesystemRejectsPathOutsideAllowlistWith400() {
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed("/etc/shadow")).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTestRequest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("/etc/shadow")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void filesystemRejectsWhenAllowlistIsNotConfiguredAtAll() {
    when(filesystemAllowlist.isConfigured()).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTestRequest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("/data/documents")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void filesystemRejectsRelativePathWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTestRequest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("relative/path")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void filesystemRejectsAnAccompanyingSourceUrlWith400() {
    // PR #537 review, nit 7: mirrors KnowledgeLibraryService#validateConfigurationForType's
    // FILESYSTEM branch - without this, a client could see a green test for a combination
    // createLibrary itself rejects with 400 right afterwards.
    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTestRequest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("/data/documents")
                        .sourceUrl(URI.create("https://files.example.com"))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void filesystemRejectsSourceInsecureSslWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTestRequest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("/data/documents")
                        .sourceInsecureSsl(true)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  // --- HTTP_DIRECTORY ------------------------------------------------------

  @Test
  void httpDirectoryReportsLinkedDocumentCount() throws IOException {
    String html =
        """
        <table>
        <tr><td><img alt="[DIR]"></td><td><a href="subdir/">subdir</a></td><td>2025-01-01</td><td>-</td></tr>
        <tr><td><img alt="[TXT]"></td><td><a href="a.txt">a.txt</a></td><td>2025-01-01</td><td>10</td></tr>
        <tr><td><img alt="[PDF]"></td><td><a href="b.pdf">b.pdf</a></td><td>2025-01-01</td><td>20</td></tr>
        </table>
        """;
    server.createContext(
        "/dir/",
        exchange -> {
          byte[] body = html.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/")));

    assertThat(response.getReachable()).isTrue();
    // 2 linked documents - the subdir entry is a directory, not a linked document.
    assertThat(response.getDocumentCount()).isEqualTo(2L);
  }

  @Test
  void httpDirectoryCountsOnlySupportedFormats() throws IOException {
    // PR #537 review, nit 4: a .zip is a linked entry the crawler sees, but not a document the
    // real UrlIndexingExecutor run would ever index - the count here must agree with the run's
    // own SupportedDocumentFormats filter, not with "every non-directory entry".
    String html =
        """
        <table>
        <tr><td><img alt="[TXT]"></td><td><a href="a.txt">a.txt</a></td><td>2025-01-01</td><td>10</td></tr>
        <tr><td><img alt="[ZIP]"></td><td><a href="b.zip">b.zip</a></td><td>2025-01-01</td><td>20</td></tr>
        </table>
        """;
    server.createContext(
        "/dir/",
        exchange -> {
          byte[] body = html.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/")));

    assertThat(response.getReachable()).isTrue();
    assertThat(response.getDocumentCount()).isEqualTo(1L);
    assertThat(response.getMessage()).contains("oberster Ebene");
  }

  @Test
  void httpDirectoryDoesNotAppendATrailingSlashForAFileLikeUrl() throws IOException {
    // PR #537 review, nit 5: mirrors UrlIndexingExecutor#execute's own normalisation
    // (hasFileExtension) - without it, "/dir/index.html" would be requested as
    // "/dir/index.html/" and 404, a false negative for a URL the later real run fetches
    // unchanged and successfully.
    String html =
        """
        <table>
        <tr><td><img alt="[TXT]"></td><td><a href="a.txt">a.txt</a></td><td>2025-01-01</td><td>10</td></tr>
        </table>
        """;
    server.createContext(
        "/dir/index.html",
        exchange -> {
          byte[] body = html.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/index.html")));

    assertThat(response.getReachable()).isTrue();
    assertThat(response.getDocumentCount()).isEqualTo(1L);
  }

  @Test
  void httpDirectoryRejectsAnOversizedResponseWithAGermanMessage() throws IOException {
    // PR #537 review, finding 2: an unbounded read would let a single request against an
    // endless/huge response crash the whole backend - bounded exactly like
    // RssFeedIndexingExecutor#readBounded/UrlFileDownloader#readBounded.
    SourceConnectionTestService tightService =
        new SourceConnectionTestService(
            new DocumentService(),
            new AutoindexCrawlerService(),
            new RssFeedParser(),
            filesystemAllowlist,
            new IndexingProperties(
                null,
                1000,
                0,
                50,
                3,
                null,
                new IndexingProperties.Rss(200, 10, 10, 0, null, null, null, 0, 0),
                null));
    String html = "<table>" + "x".repeat(100) + "</table>";
    server.createContext(
        "/dir/",
        exchange -> {
          byte[] body = html.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        tightService.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/")));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getMessage()).contains("Groesse");
  }

  @Test
  void httpDirectoryRejectsASourcePathWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTestRequest()
                        .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                        .sourceUrl(URI.create(baseUrl + "/dir/"))
                        .sourcePath("/data/documents")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void httpDirectoryReportsUnauthorizedInGerman() {
    server.createContext(
        "/dir/",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/")));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getDocumentCount()).isNull();
    assertThat(response.getMessage()).contains("401");
  }

  @Test
  void httpDirectoryReportsUnreachableHostInGerman() {
    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("http://127.0.0.1:1")));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getMessage()).isNotBlank();
    // Message must be German, human text, never a raw Java exception message.
    assertThat(response.getMessage()).doesNotContain("Exception");
  }

  @Test
  void httpDirectoryRejectsNonHttpUrlWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTestRequest()
                        .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                        .sourceUrl(URI.create("ftp://files.example.com"))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  // --- RSS_FEED --------------------------------------------------------------

  @Test
  void rssFeedReportsEntryCount() throws IOException {
    String rss =
        """
        <?xml version="1.0"?>
        <rss version="2.0"><channel>
        <title>Testfeed</title>
        <item><title>Eins</title><link>https://example.com/1</link></item>
        <item><title>Zwei</title><link>https://example.com/2</link></item>
        </channel></rss>
        """;
    server.createContext(
        "/feed.xml",
        exchange -> {
          byte[] body = rss.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml")));

    assertThat(response.getReachable()).isTrue();
    assertThat(response.getDocumentCount()).isEqualTo(2L);
  }

  @Test
  void rssFeedCapsTheReportedCountAtMaxEntries() throws IOException {
    // PR #537 review ("zwei weitere Kleinigkeiten"): mirrors
    // RssFeedIndexingExecutor#execute's own truncation - a feed carrying more entries than a
    // run ever processes must not be reported with a count the run itself never reaches.
    SourceConnectionTestService cappedService =
        new SourceConnectionTestService(
            new DocumentService(),
            new AutoindexCrawlerService(),
            new RssFeedParser(),
            filesystemAllowlist,
            new IndexingProperties(
                null,
                1000,
                0,
                50,
                3,
                null,
                new IndexingProperties.Rss(1, 0, 0, 0, null, null, null, 0, 0),
                null));
    String rss =
        """
        <?xml version="1.0"?>
        <rss version="2.0"><channel>
        <title>Testfeed</title>
        <item><title>Eins</title><link>https://example.com/1</link></item>
        <item><title>Zwei</title><link>https://example.com/2</link></item>
        </channel></rss>
        """;
    server.createContext(
        "/feed.xml",
        exchange -> {
          byte[] body = rss.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        cappedService.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml")));

    assertThat(response.getReachable()).isTrue();
    assertThat(response.getDocumentCount()).isEqualTo(1L);
    assertThat(response.getMessage()).contains("insgesamt 2");
  }

  @Test
  void rssFeedRejectsAnOversizedResponseWithAGermanMessage() throws IOException {
    SourceConnectionTestService tightService =
        new SourceConnectionTestService(
            new DocumentService(),
            new AutoindexCrawlerService(),
            new RssFeedParser(),
            filesystemAllowlist,
            new IndexingProperties(
                null,
                1000,
                0,
                50,
                3,
                null,
                new IndexingProperties.Rss(200, 10, 10, 0, null, null, null, 0, 0),
                null));
    String rss =
        "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
            + "x".repeat(50)
            + "</channel></rss>";
    server.createContext(
        "/feed.xml",
        exchange -> {
          byte[] body = rss.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        tightService.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml")));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getMessage()).contains("Groesse");
  }

  @Test
  void rssFeedRejectsASourcePathWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTestRequest()
                        .sourceType(DocumentSourceType.RSS_FEED)
                        .sourceUrl(URI.create(baseUrl + "/feed.xml"))
                        .sourcePath("/data/documents")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void rssFeedReportsUnparseableFeedInGerman() throws IOException {
    server.createContext(
        "/feed.xml",
        exchange -> {
          byte[] body = "not xml at all".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml")));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getMessage()).contains("RSS-Feed");
  }

  @Test
  void rssFeedReportsNotFoundInGerman() {
    server.createContext(
        "/feed.xml",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });

    SourceConnectionTestResponse response =
        service.test(
            new SourceConnectionTestRequest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml")));

    assertThat(response.getReachable()).isFalse();
    assertThat(response.getMessage()).contains("404");
  }

  // --- UPLOAD ------------------------------------------------------------

  @Test
  void uploadIsRejectedWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    new SourceConnectionTestRequest().sourceType(DocumentSourceType.UPLOAD)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void missingSourceTypeIsRejectedWith400() {
    assertThatThrownBy(() -> service.test(new SourceConnectionTestRequest()))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }
}
