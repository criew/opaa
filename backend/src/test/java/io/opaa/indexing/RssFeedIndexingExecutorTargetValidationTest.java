package io.opaa.indexing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryVisibility;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #267 acceptance criterion ("Die Prüfung greift auch, wenn erst eine Weiterleitung auf ein solches
 * Ziel führt"), PR #699 review finding 2: {@link RssFeedIndexingExecutorTest} disables {@link
 * TargetAddressValidator} entirely - every stub server it talks to is loopback by construction
 * (#467's own acceptance criteria) - so that suite would stay green even if the per-hop {@code
 * validate} call inside {@link AutoindexCrawlerService#sendFollowingRedirects} (used by {@code
 * RssFeedIndexingExecutor#fetchFeed}) were accidentally removed or hoisted out of the redirect
 * loop. This class exercises the check with an actually enabled validator instead.
 *
 * <p><b>Why the feed's own redirect, not the detail page's (PR #699 review, finding 2 follow-up).
 * </b> A detail-page redirect to a different host is already rejected outright by {@code
 * RssFeedIndexingExecutor#sendDetailPageRequest}'s own foreign-host check, before its per-hop
 * {@code validate} call is ever reached for that hop - a cross-origin redirect there would make a
 * naive test pass for the wrong reason (the origin check, not the target-address check) even if the
 * SSRF validation were removed. {@code fetchFeed} goes through {@link
 * AutoindexCrawlerService#sendFollowingRedirects} instead, which has no such origin restriction (it
 * only conditionally drops {@code Authorization} - see that method's own Javadoc) and therefore
 * relies on the per-hop {@code validate} call alone to reject this redirect.
 */
class RssFeedIndexingExecutorTargetValidationTest {

  private HttpServer server;
  private String baseUrl;
  private IndexingJobService indexingJobService;
  private RssFeedIndexingExecutor executor;

  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Bibliothek",
          null,
          UUID.randomUUID(),
          LibraryVisibility.PRIVATE,
          false,
          DocumentSourceType.RSS_FEED,
          null,
          "https://example.com/feed.xml",
          null,
          null,
          false);

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    FileProcessingService fileProcessingService = mock(FileProcessingService.class);
    indexingJobService = mock(IndexingJobService.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    RssFeedStateRepository feedStateRepository = mock(RssFeedStateRepository.class);
    when(feedStateRepository.findByLibraryIdAndFeedUrl(any(), anyString()))
        .thenReturn(Optional.empty());
    IndexingRunEventRepository indexingRunEventRepository = mock(IndexingRunEventRepository.class);

    // The start host (127.0.0.1, itself loopback) is allowlisted so this test isolates the
    // redirect-hop check - without allowlisting it, the very first validate() call inside
    // sendFollowingRedirects would already reject the start URL.
    TargetAddressValidator enabledValidator =
        new TargetAddressValidator(
            new IndexingProperties.TargetValidation(true, List.of("127.0.0.1")));
    IndexingProperties.Rss rss =
        new IndexingProperties.Rss(200, 10_000, 10_000, 0, "OPAA-Indexer/test", null, null, 0, 0);
    IndexingProperties properties =
        new IndexingProperties(null, 0, 0, 0, 0, null, rss, null, null, null);
    executor =
        new RssFeedIndexingExecutor(
            new RssFeedParser(),
            fileProcessingService,
            indexingJobService,
            documentRepository,
            feedStateRepository,
            new UrlFileDownloader(enabledValidator),
            properties,
            indexingRunEventRepository,
            enabledValidator);
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void feedRedirectedToABlockedTargetFailsTheRunInsteadOfBeingFetched() {
    server.createContext(
        "/feed.xml",
        exchange -> {
          // A literal IP (no DNS lookup needed, deterministic in CI) in a private range - not
          // itself on the allowlist, unlike the start host above.
          exchange.getResponseHeaders().set("Location", "http://192.168.1.1/feed.xml");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    library.updateSourceConfiguration(null, baseUrl + "/feed.xml", null, null, false);

    executor.execute(UUID.randomUUID(), library);

    verify(indexingJobService, timeout(2000))
        .failJob(
            any(),
            argThat(message -> message != null && message.contains("gesperrten Adressbereich")));
  }
}
