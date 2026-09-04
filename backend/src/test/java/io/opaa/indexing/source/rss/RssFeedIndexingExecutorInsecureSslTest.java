package io.opaa.indexing.source.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.source.web.UrlIndexingExecutor;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #637: {@link RssFeedIndexingExecutor} must honour {@code targetLibrary.isSourceInsecureSsl()} for
 * its own feed fetch exactly like {@link UrlIndexingExecutor} already does for its crawl (#505) -
 * {@link io.opaa.sourceaccess.SourceHttpClientFactory#buildHttpClient} used to be called with a
 * hardcoded {@code false} here, so a RSS_FEED library configured with {@code sourceInsecureSsl:
 * true} still rejected a self-signed certificate.
 *
 * <p><b>Not a blanket bypass (#663 review, finding 1).</b> {@code sourceInsecureSsl} must only
 * relax certificate validation for the feed's own origin, never for a foreign one an entry's {@code
 * <link>} (or an attachment URL) happens to point at - the feed operator controls that content, not
 * the library owner who configured {@code sourceInsecureSsl}.
 *
 * <p>Runs against real {@code com.sun.net.httpserver.HttpsServer} instances, each serving a freshly
 * generated, genuinely self-signed certificate (produced via {@code keytool} into a throwaway
 * {@code PKCS12} keystore, never added to the JVM's own trust store) - the assertions below
 * therefore exercise actual TLS certificate validation, not a mocked stand-in for it.
 */
class RssFeedIndexingExecutorInsecureSslTest {

  /** A self-signed {@code HttpsServer} plus the throwaway keystore file backing its certificate. */
  private record TestHttpsServer(HttpsServer server, Path keystorePath, String baseUrl) {

    void stop() {
      server.stop(0);
      try {
        Files.deleteIfExists(keystorePath);
      } catch (IOException e) {
        // Best-effort cleanup of a throwaway temp file - never fails the test suite over it.
      }
    }
  }

  private static final String EMPTY_FEED_PATH = "/empty-feed.xml";
  private static final String FEED_WITH_FOREIGN_LINK_PATH = "/feed-with-foreign-link.xml";
  private static final String FOREIGN_DETAIL_PAGE_PATH = "/a.html";

  // The feed's own origin - sourceInsecureSsl is expected to relax certificate validation here.
  private static TestHttpsServer feedServer;
  // A second, distinct origin (different port, hence a different origin per
  // RedirectFollowingFetcher#sameOrigin) simulating a foreign host a feed entry's <link> points
  // at -
  // sourceInsecureSsl must never relax validation here, no matter the library's own configuration.
  private static TestHttpsServer foreignServer;

  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private DocumentRepository documentRepository;
  private RssFeedStateRepository feedStateRepository;
  private IndexingRunEventRepository indexingRunEventRepository;
  private RssFeedIndexingExecutor executor;

  @BeforeAll
  static void startServers() throws Exception {
    feedServer = createSelfSignedHttpsServer("opaa-rss-insecure-ssl-test-feed");
    foreignServer = createSelfSignedHttpsServer("opaa-rss-insecure-ssl-test-foreign");

    serve(
        feedServer.server(),
        EMPTY_FEED_PATH,
        "application/rss+xml",
        "<rss version=\"2.0\"><channel><title>Feed</title></channel></rss>");
    serve(
        feedServer.server(),
        FEED_WITH_FOREIGN_LINK_PATH,
        "application/rss+xml",
        "<rss version=\"2.0\"><channel><title>Feed</title><item><title>Titel</title><link>"
            + foreignServer.baseUrl()
            + FOREIGN_DETAIL_PAGE_PATH
            + "</link><pubDate>Mon, 01 Jan 2024 10:00:00 GMT</pubDate></item></channel></rss>");
    serve(
        foreignServer.server(),
        FOREIGN_DETAIL_PAGE_PATH,
        "text/html",
        "<html><body><main>Fremder Inhalt</main></body></html>");

    feedServer.server().start();
    foreignServer.server().start();
  }

  @AfterAll
  static void stopServers() {
    if (feedServer != null) {
      feedServer.stop();
    }
    if (foreignServer != null) {
      foreignServer.stop();
    }
  }

  /**
   * Generates a throwaway {@code PKCS12} keystore with a genuinely self-signed certificate via
   * {@code keytool} (never trusted by the JVM's default trust store) and creates - but does not yet
   * start - an {@code HttpsServer} on {@code 127.0.0.1} using it, so the caller can register
   * contexts before {@link HttpsServer#start()}.
   */
  private static TestHttpsServer createSelfSignedHttpsServer(String alias) throws Exception {
    Path keystorePath = Files.createTempFile("opaa-rss-insecure-ssl-test-", ".p12");
    Files.delete(keystorePath); // keytool refuses to overwrite an existing empty file otherwise

    String keytool =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
    Process process =
        new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                alias,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=127.0.0.1",
                "-ext",
                "SAN=ip:127.0.0.1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystorePath.toString(),
                "-storepass",
                "changeit",
                "-keypass",
                "changeit")
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    // #663 review, finding 3: never wait unboundedly on an external process - a hung keytool would
    // otherwise hang the whole build instead of failing this test with a clear cause.
    boolean finishedInTime = process.waitFor(30, TimeUnit.SECONDS);
    if (!finishedInTime) {
      process.destroyForcibly();
      throw new IllegalStateException(
          "keytool did not finish generating a test certificate within 30 seconds: " + output);
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("keytool failed to generate a test certificate: " + output);
    }

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(keystorePath)) {
      keyStore.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, "changeit".toCharArray());
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

    HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
    String baseUrl = "https://127.0.0.1:" + server.getAddress().getPort();
    return new TestHttpsServer(server, keystorePath, baseUrl);
  }

  private static void serve(HttpsServer server, String path, String contentType, String body) {
    server.createContext(
        path,
        exchange -> {
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  @BeforeEach
  void setUp() {
    fileProcessingService = mock(FileProcessingService.class);
    indexingJobService = mock(IndexingJobService.class);
    documentRepository = mock(DocumentRepository.class);
    feedStateRepository = mock(RssFeedStateRepository.class);
    when(feedStateRepository.findByLibraryIdAndFeedUrl(any(), anyString()))
        .thenReturn(Optional.empty());
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);

    IndexingProperties.Rss rss =
        new IndexingProperties.Rss(200, 10_000, 10_000, 0, "OPAA-Indexer/test", null, null, 0, 0);
    IndexingProperties properties = new IndexingProperties(0, 0, 0, null, rss, null, null, null, 0);
    // Target validation is exercised on its own dedicated stand (TargetAddressValidatorTest) -
    // disabled here since every server this class talks to is deliberately loopback.
    TargetAddressValidator targetAddressValidator = TargetAddressValidator.disabled();
    executor =
        new RssFeedIndexingExecutor(
            new RssFeedParser(),
            fileProcessingService,
            indexingJobService,
            documentRepository,
            feedStateRepository,
            new io.opaa.indexing.source.attachment.AttachmentIndexer(
                new BoundedDownloader(targetAddressValidator),
                fileProcessingService,
                mock(LibraryStorageQuotaService.class),
                documentRepository,
                new io.opaa.indexing.source.attachment.AttachmentProperties(5)),
            properties,
            indexingRunEventRepository,
            targetAddressValidator,
            mock(LibraryStorageQuotaService.class));
  }

  private KnowledgeLibrary library(String feedUrl, boolean sourceInsecureSsl) {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Bibliothek",
        null,
        UUID.randomUUID(),
        LibraryVisibility.PRIVATE,
        false,
        DocumentSourceType.RSS_FEED,
        null,
        feedUrl,
        null,
        null,
        sourceInsecureSsl);
  }

  @Test
  void sourceInsecureSslTrueAcceptsTheSelfSignedCertificateOfTheFeedsOwnOrigin() {
    executor.execute(UUID.randomUUID(), library(feedServer.baseUrl() + EMPTY_FEED_PATH, true));

    verify(indexingJobService, timeout(5000)).completeJob(any(), eq(0), eq(0), eq(0), eq(0));
    verify(indexingJobService, never()).failJob(any(), anyString());
  }

  @Test
  void sourceInsecureSslFalseStillRejectsTheSelfSignedCertificate() {
    executor.execute(UUID.randomUUID(), library(feedServer.baseUrl() + EMPTY_FEED_PATH, false));

    ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
    verify(indexingJobService, timeout(5000)).failJob(any(), errorMessage.capture());
    verify(indexingJobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
    // Pins the failure down to the TLS certificate check this test is actually about (#663 review,
    // finding 2) - a failJob(...) call for any other reason (e.g. a typo in the feed URL) must not
    // leave this test green.
    String lowerCased = errorMessage.getValue().toLowerCase(Locale.ROOT);
    assertThat(
            lowerCased.contains("pkix")
                || lowerCased.contains("certificat")
                || lowerCased.contains("ssl"))
        .as(
            "failJob message should mention the TLS certificate failure, was: %s",
            errorMessage.getValue())
        .isTrue();
  }

  @Test
  void sourceInsecureSslTrueDoesNotWeakenValidationForAForeignOriginDetailPage() {
    // feedServer's own certificate is trusted (sourceInsecureSsl: true), but the feed's single
    // entry links to foreignServer - a different origin (#663 review, finding 1) whose self-signed
    // certificate must still be validated normally, exactly as it would be with
    // sourceInsecureSsl: false.
    executor.execute(
        UUID.randomUUID(), library(feedServer.baseUrl() + FEED_WITH_FOREIGN_LINK_PATH, true));

    // The run still completes - a foreign detail page's TLS failure only skips that one entry, it
    // never fails the whole run (ADR-0017's "Verhalten gegenüber fremden Zielen").
    verify(indexingJobService, timeout(5000)).completeJob(any(), eq(0), eq(0), eq(1), eq(0));
    verify(fileProcessingService, never())
        .processRssEntry(anyString(), anyString(), anyString(), any(), any());
  }
}
