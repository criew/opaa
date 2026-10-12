package io.opaa.indexing.source.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.sourceaccess.UntrustedCertificate;
import io.opaa.test.LoopbackTls;
import io.opaa.test.SourceTypes;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * An RSS run against a feed whose certificate no known authority issued fails, and the failure
 * names the untrusted certificate with the hint to add the own authority - there is no way to skip
 * the check. The feed runs on a real {@code HttpsServer} with the self-signed loopback certificate.
 */
class RssFeedIndexingExecutorUntrustedCertificateTest {

  private static final String FEED_PATH = "/feed.xml";

  private static HttpsServer feedServer;

  private IndexingJobService indexingJobService;
  private RssFeedIndexingExecutor executor;

  @BeforeAll
  static void startServer() throws Exception {
    feedServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    feedServer.setHttpsConfigurator(new HttpsConfigurator(LoopbackTls.serverContext()));
    feedServer.createContext(
        FEED_PATH,
        exchange -> {
          byte[] bytes =
              "<rss version=\"2.0\"><channel><title>Feed</title></channel></rss>"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    feedServer.start();
  }

  @AfterAll
  static void stopServer() {
    if (feedServer != null) {
      feedServer.stop(0);
    }
  }

  @BeforeEach
  void setUp() {
    DocumentIngestService documentIngestService = mock(DocumentIngestService.class);
    indexingJobService = mock(IndexingJobService.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    RssFeedStateRepository feedStateRepository = mock(RssFeedStateRepository.class);
    when(feedStateRepository.findByLibraryIdAndFeedUrl(any(), anyString()))
        .thenReturn(Optional.empty());

    RssFeedProperties rss = new RssFeedProperties(200, 10_000, 10_000, 0, null, null, 0, 0);
    // the feed is deliberately on loopback; target validation has its own tests
    TargetAddressValidator targetAddressValidator = TargetAddressValidator.disabled();
    executor =
        new RssFeedIndexingExecutor(
            new RssFeedParser(),
            documentIngestService,
            documentRepository,
            feedStateRepository,
            new io.opaa.indexing.document.AttachmentIndexer(
                new BoundedDownloader(targetAddressValidator),
                documentIngestService,
                mock(LibraryStorageQuotaService.class),
                new io.opaa.indexing.attachment.AttachmentProperties(5, 0, 0),
                io.opaa.test.ProductionDocumentFormats.supportedFormats()),
            rss,
            targetAddressValidator,
            new SourceRequestPolicy("OPAA-Indexer/test", RateLimitPolicy.NONE, duration -> {}),
            new IndexingRunTemplate(
                indexingJobService,
                mock(IndexingRunEventRepository.class),
                mock(io.opaa.indexing.maintenance.StaleDocumentCleanupService.class),
                documentRepository,
                mock(LibraryStorageQuotaService.class),
                new LibrarySourceConnectionResolver()));
  }

  @Test
  void aSelfSignedCertificateFailsTheRunWithTheHintToAddTheOwnAuthority() throws Exception {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            SourceTypes.RSS_FEED,
            null,
            "https://127.0.0.1:" + feedServer.getAddress().getPort() + FEED_PATH,
            null);

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.INCREMENTAL);

    ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
    verify(indexingJobService, timeout(5000)).failJob(any(), errorMessage.capture());
    verify(indexingJobService, never()).completeJob(any(), anyInt(), anyInt(), anyInt(), anyInt());
    assertThat(errorMessage.getValue()).isEqualTo(UntrustedCertificate.MESSAGE);
  }
}
