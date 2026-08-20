package io.opaa.indexing;

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
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryVisibility;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Optional;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #637: {@link RssFeedIndexingExecutor} must honour {@code targetLibrary.isSourceInsecureSsl()} for
 * its own feed fetch exactly like {@link UrlIndexingExecutor} already does for its crawl (#505) -
 * {@link AutoindexCrawlerService#buildHttpClient} used to be called with a hardcoded {@code false}
 * here, so a RSS_FEED library configured with {@code sourceInsecureSsl: true} still rejected a
 * self-signed certificate.
 *
 * <p>Runs against a real {@code com.sun.net.httpserver.HttpsServer} serving a freshly generated,
 * genuinely self-signed certificate (produced via {@code keytool} into a throwaway {@code PKCS12}
 * keystore, never added to the JVM's own trust store) - the assertion below therefore exercises
 * actual TLS certificate validation, not a mocked stand-in for it.
 */
class RssFeedIndexingExecutorInsecureSslTest {

  private static Path keystorePath;
  private static HttpsServer server;
  private static String baseUrl;

  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private DocumentRepository documentRepository;
  private RssFeedStateRepository feedStateRepository;
  private IndexingRunEventRepository indexingRunEventRepository;
  private RssFeedIndexingExecutor executor;

  @BeforeAll
  static void startServer() throws Exception {
    keystorePath = Files.createTempFile("opaa-rss-insecure-ssl-test-", ".p12");
    Files.delete(keystorePath); // keytool refuses to overwrite an existing empty file otherwise

    String keytool =
        System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
    Process process =
        new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-alias",
                "opaa-rss-insecure-ssl-test",
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
    int exitCode = process.waitFor();
    if (exitCode != 0) {
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

    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
    server.createContext(
        "/feed.xml",
        exchange -> {
          byte[] bytes =
              "<rss version=\"2.0\"><channel><title>Feed</title></channel></rss>"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    baseUrl = "https://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() throws IOException {
    if (server != null) {
      server.stop(0);
    }
    if (keystorePath != null) {
      Files.deleteIfExists(keystorePath);
    }
  }

  @BeforeEach
  void setUp() {
    fileProcessingService = mock(FileProcessingService.class);
    indexingJobService = mock(IndexingJobService.class);
    documentRepository = mock(DocumentRepository.class);
    feedStateRepository = mock(RssFeedStateRepository.class);
    when(feedStateRepository.findByFeedUrl(anyString())).thenReturn(Optional.empty());
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);

    IndexingProperties.Rss rss =
        new IndexingProperties.Rss(200, 10_000, 10_000, 0, "OPAA-Indexer/test", null, null, 0, 0);
    IndexingProperties properties = new IndexingProperties(null, 0, 0, 0, 0, null, rss, null, null);
    executor =
        new RssFeedIndexingExecutor(
            new RssFeedParser(),
            fileProcessingService,
            indexingJobService,
            documentRepository,
            feedStateRepository,
            new UrlFileDownloader(),
            properties,
            indexingRunEventRepository);
  }

  @AfterEach
  void tearDown() {
    // No per-test resources beyond the mocks above; the HTTPS server and keystore are shared and
    // torn down once in stopServer().
  }

  private KnowledgeLibrary library(boolean sourceInsecureSsl) {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Bibliothek",
        null,
        UUID.randomUUID(),
        LibraryVisibility.PRIVATE,
        false,
        DocumentSourceType.RSS_FEED,
        null,
        baseUrl + "/feed.xml",
        null,
        null,
        sourceInsecureSsl);
  }

  @Test
  void sourceInsecureSslTrueAcceptsTheSelfSignedCertificate() {
    executor.execute(UUID.randomUUID(), library(true));

    verify(indexingJobService, timeout(5000)).completeJob(any(), eq(0), eq(0), eq(0), eq(0));
    verify(indexingJobService, never()).failJob(any(), anyString());
  }

  @Test
  void sourceInsecureSslFalseStillRejectsTheSelfSignedCertificate() {
    executor.execute(UUID.randomUUID(), library(false));

    verify(indexingJobService, timeout(5000)).failJob(any(), anyString());
    verify(indexingJobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
  }
}
