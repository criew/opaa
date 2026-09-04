package io.opaa.library;

import static io.opaa.library.SourceConnectionTestBuilder.sourceConnectionTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.DocumentService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.indexing.source.rss.RssFeedParser;
import io.opaa.indexing.source.web.AutoindexCrawlerService;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  private KnowledgeLibraryRepository libraryRepository;
  private LibraryAccessService libraryAccessService;
  private SourceConnectionTestService service;
  private UUID currentUserId;
  private UUID organizationId;
  private CurrentUser caller;
  private CurrentUser systemAdminCaller;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    libraryAccessService = mock(LibraryAccessService.class);
    currentUserId = UUID.randomUUID();
    organizationId = UUID.randomUUID();
    caller = CurrentUser.of(currentUserId, organizationId, SystemRole.USER, "Caller");
    systemAdminCaller =
        CurrentUser.of(currentUserId, organizationId, SystemRole.SYSTEM_ADMIN, "Caller");
    service =
        new SourceConnectionTestService(
            new DocumentService(),
            new AutoindexCrawlerService(TargetAddressValidator.disabled()),
            new RssFeedParser(),
            filesystemAllowlist,
            libraryRepository,
            libraryAccessService,
            new IndexingProperties(1000, 0, 50, null, null, null, null, null, 0),
            TargetAddressValidator.disabled());
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
    Files.write(dir.resolve("b.pdf"), "%PDF-1.4\n%mock-pdf-body".getBytes(StandardCharsets.UTF_8));
    // #404: c.xyz is unsupported by its actual content (arbitrary binary, no accepted media type
    // matches) - a supported-looking extension on genuinely readable text (the old fixture used
    // "unsupported" as literal file content) would now be accepted, since content decides.
    Files.write(dir.resolve("c.xyz"), new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0, 1, 2, 3});

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.FILESYSTEM)
                .sourcePath(dir.toString())
                .build());

    assertThat(response.reachable()).isTrue();
    assertThat(response.documentCount()).isEqualTo(2L);
    assertThat(response.message()).contains("2").contains("Dokumente");
  }

  @Test
  void filesystemReportsUnreachableForMissingDirectory(@TempDir Path dir) {
    String missing = dir.resolve("does-not-exist").toString();
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed(missing)).thenReturn(true);

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.FILESYSTEM)
                .sourcePath(missing)
                .build());

    assertThat(response.reachable()).isFalse();
    assertThat(response.documentCount()).isNull();
    assertThat(response.message()).isEqualTo("Das Verzeichnis existiert nicht.");
  }

  @Test
  void filesystemRejectsPathOutsideAllowlistWith400() {
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed("/etc/shadow")).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("/etc/shadow")
                        .build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void filesystemRejectsWhenAllowlistIsNotConfiguredAtAll() {
    when(filesystemAllowlist.isConfigured()).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("/data/documents")
                        .build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void filesystemRejectsRelativePathWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("relative/path")
                        .build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void filesystemRejectsAnAccompanyingSourceUrlWith400() {
    // PR #537 review, nit 7: mirrors KnowledgeLibraryService#validateConfigurationForType's
    // FILESYSTEM branch - without this, a client could see a green test for a combination
    // createLibrary itself rejects with 400 right afterwards.
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("/data/documents")
                        .sourceUrl(URI.create("https://files.example.com"))
                        .build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void filesystemRejectsSourceInsecureSslWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("/data/documents")
                        .sourceInsecureSsl(true)
                        .build()))
        .isInstanceOf(ValidationException.class);
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

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/"))
                .build());

    assertThat(response.reachable()).isTrue();
    // 2 linked documents - the subdir entry is a directory, not a linked document.
    assertThat(response.documentCount()).isEqualTo(2L);
    // #551: exact wording, incl. plural adjective agreement ("unterstützte Dokumente").
    assertThat(response.message())
        .isEqualTo(
            "Webverzeichnis erreichbar, 2 unterstützte Dokumente auf oberster Ebene gefunden.");
  }

  @Test
  void httpDirectoryReportsLinkedDocumentCountForApachePreLayout() throws IOException {
    // #550: Apache mod_autoindex without "IndexOptions HTMLTable" - a <pre> listing, not a table.
    String html =
        """
        <html><head><title>Index of /dir/</title></head><body>
        <pre><a href="/">Parent Directory</a>
        <a href="a.txt">a.txt</a>            2025-01-01 00:00  10
        <a href="b.pdf">b.pdf</a>            2025-01-01 00:00  20
        </pre></body></html>
        """;
    server.createContext(
        "/dir/",
        exchange -> {
          byte[] body = html.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/"))
                .build());

    assertThat(response.reachable()).isTrue();
    assertThat(response.documentCount()).isEqualTo(2L);
  }

  @Test
  void httpDirectoryReportsLinkedDocumentCountForUlLayout() throws IOException {
    // #550: Python http.server / Apache "-FancyIndexing" - a plain <ul>, no HTMLTable at all.
    String html =
        """
        <html><head><title>Directory listing for /dir/</title></head><body>
        <ul>
        <li><a href="a.txt">a.txt</a></li>
        <li><a href="b.pdf">b.pdf</a></li>
        </ul></body></html>
        """;
    server.createContext(
        "/dir/",
        exchange -> {
          byte[] body = html.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/"))
                .build());

    assertThat(response.reachable()).isTrue();
    assertThat(response.documentCount()).isEqualTo(2L);
  }

  @Test
  void httpDirectoryReportsAnExplanatoryHintForAnUnrecognizedPage() throws IOException {
    // #550: a page that is reachable but isn't a directory listing this class recognizes at all
    // (e.g. a login page) must not be reported as "0 unterstuetzte Dokumente gefunden" - that
    // reads like a successful, merely-empty directory instead of a configuration problem.
    String html =
        """
        <html><head><title>Welcome</title></head>
        <body><p>This is just a website, not a directory listing.</p></body></html>
        """;
    server.createContext(
        "/dir/",
        exchange -> {
          byte[] body = html.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/"))
                .build());

    assertThat(response.reachable()).isFalse();
    assertThat(response.message()).contains("kein erkennbares Verzeichnislisting");
  }

  @Test
  void httpDirectoryFollowsASameOriginRedirect() throws IOException {
    // #538 follow-up review, finding 4: SourceConnectionTestService had no redirect test at all -
    // buildHttpClient no longer auto-follows at the JDK level (Redirect.NEVER), so a legitimate
    // same-origin redirect (e.g. a trailing slash added by the server itself) must still be chased
    // by RedirectFollowingFetcher.sendFollowingRedirects.
    String html =
        """
        <table>
        <tr><td><img alt="[TXT]"></td><td><a href="a.txt">a.txt</a></td><td>2025-01-01</td><td>10</td></tr>
        </table>
        """;
    // testHttpDirectory itself appends a trailing slash before ever sending a request (mirrors
    // UrlIndexingExecutor) - the redirect context is therefore registered at "/dir-old/" (what the
    // service actually requests), not at the un-normalized "/dir-old" the test passes in.
    server.createContext(
        "/dir-old/",
        exchange -> {
          exchange.getResponseHeaders().set("Location", baseUrl + "/dir/");
          exchange.sendResponseHeaders(301, -1);
          exchange.close();
        });
    server.createContext(
        "/dir/",
        exchange -> {
          byte[] body = html.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir-old"))
                .build());

    assertThat(response.reachable()).isTrue();
    assertThat(response.documentCount()).isEqualTo(1L);
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

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/"))
                .build());

    assertThat(response.reachable()).isTrue();
    assertThat(response.documentCount()).isEqualTo(1L);
    // #551: the singular defect the issue is named after - "unterstütztes Dokument", not
    // "unterstuetzte Dokument".
    assertThat(response.message())
        .isEqualTo(
            "Webverzeichnis erreichbar, 1 unterstütztes Dokument auf oberster Ebene gefunden.");
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

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/index.html"))
                .build());

    assertThat(response.reachable()).isTrue();
    assertThat(response.documentCount()).isEqualTo(1L);
  }

  @Test
  void httpDirectoryRejectsAnOversizedResponseWithAGermanMessage() throws IOException {
    // PR #537 review, finding 2: an unbounded read would let a single request against an
    // endless/huge response crash the whole backend - bounded exactly like
    // RssFeedIndexingExecutor#readBounded/BoundedDownloader#readBounded.
    SourceConnectionTestService tightService =
        new SourceConnectionTestService(
            new DocumentService(),
            new AutoindexCrawlerService(TargetAddressValidator.disabled()),
            new RssFeedParser(),
            filesystemAllowlist,
            libraryRepository,
            libraryAccessService,
            new IndexingProperties(
                1000,
                0,
                50,
                null,
                new IndexingProperties.Rss(200, 10, 10, 0, null, null, null, 0, 0),
                null,
                null,
                null,
                0),
            TargetAddressValidator.disabled());
    String html = "<table>" + "x".repeat(100) + "</table>";
    server.createContext(
        "/dir/",
        exchange -> {
          byte[] body = html.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    SourceConnectionTestResult response =
        tightService.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/"))
                .build());

    assertThat(response.reachable()).isFalse();
    assertThat(response.message()).contains("Größe");
  }

  @Test
  void httpDirectoryRejectsASourcePathWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                        .sourceUrl(URI.create(baseUrl + "/dir/"))
                        .sourcePath("/data/documents")
                        .build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void httpDirectoryReportsUnauthorizedInGerman() {
    server.createContext(
        "/dir/",
        exchange -> {
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/"))
                .build());

    assertThat(response.reachable()).isFalse();
    assertThat(response.documentCount()).isNull();
    assertThat(response.message()).contains("401");
  }

  @Test
  void httpDirectoryReportsUnreachableHostInGerman() {
    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("http://127.0.0.1:1"))
                .build());

    assertThat(response.reachable()).isFalse();
    assertThat(response.message()).isNotBlank();
    // Message must be German, human text, never a raw Java exception message.
    assertThat(response.message()).doesNotContain("Exception");
  }

  @Test
  void httpDirectoryRejectsNonHttpUrlWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                        .sourceUrl(URI.create("ftp://files.example.com"))
                        .build()))
        .isInstanceOf(ValidationException.class);
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

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml"))
                .build());

    assertThat(response.reachable()).isTrue();
    assertThat(response.documentCount()).isEqualTo(2L);
  }

  @Test
  void rssFeedCapsTheReportedCountAtMaxEntries() throws IOException {
    // PR #537 review ("zwei weitere Kleinigkeiten"): mirrors
    // RssFeedIndexingExecutor#execute's own truncation - a feed carrying more entries than a
    // run ever processes must not be reported with a count the run itself never reaches.
    SourceConnectionTestService cappedService =
        new SourceConnectionTestService(
            new DocumentService(),
            new AutoindexCrawlerService(TargetAddressValidator.disabled()),
            new RssFeedParser(),
            filesystemAllowlist,
            libraryRepository,
            libraryAccessService,
            new IndexingProperties(
                1000,
                0,
                50,
                null,
                new IndexingProperties.Rss(1, 0, 0, 0, null, null, null, 0, 0),
                null,
                null,
                null,
                0),
            TargetAddressValidator.disabled());
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

    SourceConnectionTestResult response =
        cappedService.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml"))
                .build());

    assertThat(response.reachable()).isTrue();
    assertThat(response.documentCount()).isEqualTo(1L);
    // #551: exact wording, incl. correct umlauts ("enthält", "Einträge", "höchstens").
    assertThat(response.message())
        .isEqualTo(
            "RSS-Feed erreichbar, 1 Eintrag gefunden. Der Feed enthält insgesamt 2 Einträge; ein"
                + " Lauf verarbeitet davon höchstens 1.");
  }

  @Test
  void rssFeedRejectsAnOversizedResponseWithAGermanMessage() throws IOException {
    SourceConnectionTestService tightService =
        new SourceConnectionTestService(
            new DocumentService(),
            new AutoindexCrawlerService(TargetAddressValidator.disabled()),
            new RssFeedParser(),
            filesystemAllowlist,
            libraryRepository,
            libraryAccessService,
            new IndexingProperties(
                1000,
                0,
                50,
                null,
                new IndexingProperties.Rss(200, 10, 10, 0, null, null, null, 0, 0),
                null,
                null,
                null,
                0),
            TargetAddressValidator.disabled());
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

    SourceConnectionTestResult response =
        tightService.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml"))
                .build());

    assertThat(response.reachable()).isFalse();
    assertThat(response.message()).contains("Größe");
  }

  @Test
  void rssFeedRejectsASourcePathWith400() {
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.RSS_FEED)
                        .sourceUrl(URI.create(baseUrl + "/feed.xml"))
                        .sourcePath("/data/documents")
                        .build()))
        .isInstanceOf(ValidationException.class);
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

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml"))
                .build());

    assertThat(response.reachable()).isFalse();
    assertThat(response.message()).contains("RSS-Feed");
  }

  @Test
  void rssFeedReportsNotFoundInGerman() {
    server.createContext(
        "/feed.xml",
        exchange -> {
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });

    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create(baseUrl + "/feed.xml"))
                .build());

    assertThat(response.reachable()).isFalse();
    assertThat(response.message()).contains("404");
  }

  // --- libraryId (#544) ---------------------------------------------------

  @Test
  void libraryIdFallsBackToStoredCredentialsWhenRequestOmitsThem() throws IOException {
    UUID libraryId = UUID.randomUUID();
    String expectedAuth =
        "Basic "
            + Base64.getEncoder().encodeToString("admin:secret".getBytes(StandardCharsets.UTF_8));
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            currentUserId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            baseUrl + "/dir/",
            null,
            "admin:secret",
            false);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);
    server.createContext(
        "/dir/",
        exchange -> {
          String actualAuth = exchange.getRequestHeaders().getFirst("Authorization");
          if (!expectedAuth.equals(actualAuth)) {
            exchange.sendResponseHeaders(401, -1);
          } else {
            // #550: an empty <table> alone no longer counts as a recognized listing (needed so
            // the connection test can tell a genuinely empty directory apart from a page that
            // isn't a listing at all) - the title marks this fixture as one without adding any
            // linked entries, which is all this test cares about (the Authorization header).
            byte[] body =
                "<html><head><title>Index of /dir/</title></head><body><table></table></body>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });

    // No sourceCredentials on the request - #544 falls back to the library's stored one because
    // sourceUrl still names the same origin as the library's own stored sourceUrl.
    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/"))
                .libraryId(libraryId)
                .build(),
            caller);

    assertThat(response.reachable()).isTrue();
  }

  @Test
  void libraryIdDoesNotFallBackToStoredCredentialsForADifferentOrigin() throws IOException {
    // #615 review, finding 2: the central security promise - a request whose sourceUrl no longer
    // names the library's stored origin must never see the stored credentials, even with
    // libraryId set - was previously untested. A second HttpServer instance stands in for a
    // foreign host; only the port differs from baseUrl, which SourceOriginMatcher's port
    // normalization must still catch (#542 review finding 1's same-origin rule, delegated to
    // RedirectFollowingFetcher#sameOrigin).
    UUID libraryId = UUID.randomUUID();
    HttpServer otherServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    otherServer.start();
    try {
      String otherBaseUrl = "http://127.0.0.1:" + otherServer.getAddress().getPort();
      KnowledgeLibrary library =
          KnowledgeLibrary.ownedByUser(
              organizationId,
              "Bibliothek",
              null,
              currentUserId,
              LibraryVisibility.PRIVATE,
              false,
              DocumentSourceType.HTTP_DIRECTORY,
              null,
              baseUrl + "/dir/",
              null,
              "admin:secret",
              false);
      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
          .thenReturn(AssetRole.MANAGER);
      AtomicReference<String> observedAuth = new AtomicReference<>();
      otherServer.createContext(
          "/dir/",
          exchange -> {
            observedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
          });

      // sourceUrl points at otherBaseUrl (a different port, everything else identical) - not the
      // library's own stored baseUrl.
      SourceConnectionTestResult response =
          service.test(
              sourceConnectionTest()
                  .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                  .sourceUrl(URI.create(otherBaseUrl + "/dir/"))
                  .libraryId(libraryId)
                  .build(),
              caller);

      assertThat(response.reachable()).isFalse();
      assertThat(observedAuth.get()).isNull();
    } finally {
      otherServer.stop(0);
    }
  }

  @Test
  void libraryIdFallbackForcesTheLibrarysStoredProxyAndInsecureSslInsteadOfTheCallers()
      throws IOException {
    // #617: the origin check above bounds the *target* the stored credential may be tested
    // against, but says nothing about the *path* the request travels to get there. A caller who
    // does not know the stored credential (only enough access to trigger this fallback) could
    // otherwise still set their own sourceProxy/sourceInsecureSsl on the very same, same-origin
    // request and have the stored Basic-Auth credential replayed through a connection they
    // control. Reproduced here with a caller-supplied proxy nothing listens on: before the fix,
    // that bogus proxy is what buildHttpClient actually uses, and the request never reaches the
    // real server at all (unreachable) - after the fix, the library's own stored proxy (none) is
    // used instead, the real server is reached directly, and the stored credential is still the
    // one sent.
    UUID libraryId = UUID.randomUUID();
    String expectedAuth =
        "Basic "
            + Base64.getEncoder().encodeToString("admin:secret".getBytes(StandardCharsets.UTF_8));
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            currentUserId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            baseUrl + "/dir/",
            null, // stored sourceProxy: none
            "admin:secret",
            false); // stored sourceInsecureSsl: false
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);
    AtomicReference<String> observedAuth = new AtomicReference<>();
    server.createContext(
        "/dir/",
        exchange -> {
          observedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body =
              "<html><head><title>Index of /dir/</title></head><body><table></table></body>"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });

    // No sourceCredentials (falls back to the library's stored one), but a caller-supplied
    // sourceProxy nothing listens on - port 1 is a well-known reserved TCP port real services do
    // not bind, so connecting through it fails fast rather than hanging until a timeout.
    SourceConnectionTestResult response =
        service.test(
            sourceConnectionTest()
                .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create(baseUrl + "/dir/"))
                .sourceProxy("127.0.0.1:1")
                .sourceInsecureSsl(true)
                .libraryId(libraryId)
                .build(),
            caller);

    assertThat(response.reachable()).isTrue();
    assertThat(observedAuth.get()).isEqualTo(expectedAuth);
  }

  @Test
  void libraryIdWithSystemAdminReachesTheSameRequireRoleCallAsAnOrdinaryManager() {
    // #615 review, finding 3: LibraryController passes the same systemAdmin flag to this test as
    // to KnowledgeLibraryService#updateLibrary for the save it precedes - hard-coding false here
    // would let a SYSTEM_ADMIN save a quellkonfiguration without a grant but see 404 from
    // "Verbindung testen" right before it.
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            "/data/documents",
            null,
            null,
            null,
            false);
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed("/data/documents")).thenReturn(false);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUserId, true, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenThrow(new NotFoundException("Bibliothek nicht gefunden"));

    // systemAdmin=false would answer 404 here (no grant on this library at all); systemAdmin=true
    // reaches past requireRole into the actual FILESYSTEM check below (400, allowlist gate) -
    // proof that the systemAdmin flag threads all the way from the public test(...) overload into
    // requireManagedLibrary.
    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.FILESYSTEM)
                        .sourcePath("/data/documents")
                        .libraryId(libraryId)
                        .build(),
                    systemAdminCaller))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void libraryIdBelowManagerIsRejectedWith403() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            currentUserId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            baseUrl + "/dir/",
            null,
            "admin:secret",
            false);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenThrow(new AccessDeniedException("Kein Zugriff auf diese Bibliothek"));

    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                        .sourceUrl(URI.create(baseUrl + "/dir/"))
                        .libraryId(libraryId)
                        .build(),
                    caller))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void libraryIdOfUnknownLibraryIsRejectedWith404() {
    UUID libraryId = UUID.randomUUID();
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                        .sourceUrl(URI.create(baseUrl + "/dir/"))
                        .libraryId(libraryId)
                        .build(),
                    caller))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void libraryIdWithMismatchedSourceTypeIsRejectedWith400() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            currentUserId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.RSS_FEED,
            null,
            baseUrl + "/feed.xml",
            null,
            "admin:secret",
            false);
    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    when(libraryAccessService.requireRole(library, currentUserId, false, AssetRole.MANAGER))
        .thenReturn(AssetRole.MANAGER);

    assertThatThrownBy(
            () ->
                service.test(
                    sourceConnectionTest()
                        .sourceType(DocumentSourceType.HTTP_DIRECTORY)
                        .sourceUrl(URI.create(baseUrl + "/dir/"))
                        .libraryId(libraryId)
                        .build(),
                    caller))
        .isInstanceOf(ValidationException.class);
  }

  // --- UPLOAD ------------------------------------------------------------

  @Test
  void uploadIsRejectedWith400() {
    assertThatThrownBy(
            () ->
                service.test(sourceConnectionTest().sourceType(DocumentSourceType.UPLOAD).build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void missingSourceTypeIsRejectedWith400() {
    assertThatThrownBy(() -> service.test(sourceConnectionTest().build()))
        .isInstanceOf(ValidationException.class);
  }
}
