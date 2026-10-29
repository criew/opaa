package io.opaa.indexing.source.rss;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.opaa.common.ValidationException;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.RemoteOriginalAccess;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.RateLimitPolicy;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.test.SourceTypes;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The feed connector's own settings - content selector and attachment profile of one library: how
 * they are read, what the audit names, which run state a change discards, and what the connection
 * test reports about them. Against a loopback {@link HttpServer}, never a real address.
 */
class RssFeedSourceConnectorSettingsTest {

  private HttpServer server;
  private String baseUrl;
  private RssFeedStateRepository feedStateRepository;
  private RssFeedSourceConnector connector;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    feedStateRepository = mock(RssFeedStateRepository.class);
    when(feedStateRepository.findByLibraryIdAndFeedUrl(any(), any())).thenReturn(Optional.empty());
    connector =
        new RssFeedSourceConnector(
            new RssFeedParser(),
            feedStateRepository,
            TargetAddressValidator.disabled(),
            new SourceRequestPolicy(
                "OPAA-Indexer/test", RateLimitPolicy.of(2, Duration.ofSeconds(1)), wait -> {}),
            new RssFeedProperties(200, 100_000, 100_000, 0, 10, 100_000),
            mock(RemoteOriginalAccess.class));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  @Test
  void readsItsTwoSettingsAndRefusesAnInvalidSelector() {
    ConnectorData read =
        connector.readSettings(
            ConnectorData.of(
                Map.of("mainContentSelector", " .inhalt ", "attachmentProfile", "GSB")));

    assertThat(read.asMap())
        .containsEntry("mainContentSelector", ".inhalt")
        .containsEntry("attachmentProfile", "GSB");
    assertThatThrownBy(
            () -> connector.readSettings(ConnectorData.of(Map.of("mainContentSelector", "div["))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void theAuditNamesBothSettingsByTheirFieldName() {
    KnowledgeLibrary library = library(baseUrl + "/feed.xml");

    assertThat(connector.settingsState(library, null))
        .containsOnlyKeys(RssFeedSettings.MAIN_CONTENT_SELECTOR, RssFeedSettings.ATTACHMENT_PROFILE)
        .containsEntry(RssFeedSettings.MAIN_CONTENT_SELECTOR, null)
        .containsEntry(RssFeedSettings.ATTACHMENT_PROFILE, null);
    assertThat(
            connector.settingsState(
                library,
                ConnectorData.of(
                    Map.of("mainContentSelector", "#inhalt", "attachmentProfile", "GSB"))))
        .containsEntry(RssFeedSettings.MAIN_CONTENT_SELECTOR, "#inhalt")
        .containsEntry(RssFeedSettings.ATTACHMENT_PROFILE, "GSB");
  }

  /**
   * The feed state names the settings it was saved under; the next run compares them with the
   * library's own. A connector reads nothing but the path from the library, so a change writes no
   * state of its own.
   */
  @Test
  void aChangedSettingLeavesTheComparisonToTheNextRun() {
    connector.onSourceChanged(
        library(baseUrl + "/feed.xml"), false, Set.of(RssFeedSettings.MAIN_CONTENT_SELECTOR));

    verify(feedStateRepository, never()).save(any());
    verify(feedStateRepository, never()).deleteByLibraryId(any());
  }

  @Test
  void aNewAddressDropsTheOldStateAlsoWithNewSettings() {
    KnowledgeLibrary library = library(baseUrl + "/neu.xml");

    connector.onSourceChanged(library, true, Set.of(RssFeedSettings.ATTACHMENT_PROFILE));

    verify(feedStateRepository).deleteByLibraryId(library.getId());
    verify(feedStateRepository, never()).save(any());
  }

  @Test
  void anUnrelatedChangeLeavesTheFeedStateAlone() {
    connector.onSourceChanged(library(baseUrl + "/feed.xml"), false, Set.of());

    verify(feedStateRepository, never()).save(any());
    verify(feedStateRepository, never()).deleteByLibraryId(any());
  }

  @Test
  void theTestReportsWhatTheSelectorFindsOnTheFirstEntrysDetailPage() {
    serveFeed("/a.html", "/b.html");
    serve(
        "/a.html",
        "<html><body><main>Rahmen</main>"
            + "<div class=\"inhalt\">Eins</div><div class=\"inhalt\">Zwei</div></body></html>");

    SourceConnectionTestResult result =
        connector.testConnection(feed(Map.of("mainContentSelector", ".inhalt")), null);

    assertThat(result.reachable()).isTrue();
    assertThat(result.documentCount()).isEqualTo(2L);
    assertThat(result.message())
        .startsWith("RSS-Feed erreichbar, 2 Einträge gefunden.")
        .contains("Inhaltsselektor findet auf der Detailseite des ersten Eintrags 2 Bereiche");
    assertThat(detailPage(result))
        .containsEntry("selectorMatched", true)
        .containsEntry("contentAreas", 2);
  }

  @Test
  void theTestSaysWhenTheSelectorFindsNothingAndARunWouldTakeTheWholePage() {
    serveFeed("/a.html");
    serve("/a.html", "<html><body><main>Text</main></body></html>");

    SourceConnectionTestResult result =
        connector.testConnection(feed(Map.of("mainContentSelector", "#fehlt")), null);

    assertThat(result.reachable()).isTrue();
    assertThat(result.message())
        .contains("Der Inhaltsselektor findet auf der Detailseite des ersten Eintrags nichts")
        .contains("ganze Seite");
    assertThat(detailPage(result)).containsEntry("selectorMatched", false);
  }

  @Test
  void theTestCountsTheAttachmentsTheChosenProfileFinds() {
    serveFeed("/a.html");
    serve(
        "/a.html",
        "<html><body><main>Text<a href=\""
            + baseUrl
            + "/service/bescheid?__blob=publicationFile\">Bescheid</a>"
            + "<a href=\""
            + baseUrl
            + "/anlage.pdf\">PDF</a></main></body></html>");

    SourceConnectionTestResult result =
        connector.testConnection(feed(Map.of("attachmentProfile", "GSB")), null);

    assertThat(result.message()).contains("1 Anlage nach dem Anlagenprofil GSB");
    assertThat(detailPage(result)).containsEntry("attachments", 1);
  }

  @Test
  void theStoredSettingsCountWhenTheRequestCarriesNone() {
    serveFeed("/a.html");
    serve("/a.html", "<html><body><div id=\"inhalt\">Text</div></body></html>");

    SourceConnectionTestResult result =
        connector.testConnection(
            feed(null), ConnectorData.of(Map.of("mainContentSelector", "#inhalt")));

    assertThat(detailPage(result)).containsEntry("selectorMatched", true);
  }

  @Test
  void aDetailPageThatCannotBeReadLeavesTheFeedReachable() {
    serveFeed("/a.html");
    server.createContext(
        "/a.html",
        exchange -> {
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });

    SourceConnectionTestResult result =
        connector.testConnection(feed(Map.of("mainContentSelector", "#inhalt")), null);

    assertThat(result.reachable()).isTrue();
    assertThat(result.message())
        .contains("Die Detailseite des ersten Eintrags konnte nicht geprüft werden");
  }

  /** Under the factory defaults the test stays one request to the feed, as before. */
  @Test
  void withoutOwnSettingsTheTestNeverOpensADetailPage() {
    serveFeed("/a.html");
    AtomicInteger detailPageHits = new AtomicInteger();
    server.createContext(
        "/a.html",
        exchange -> {
          detailPageHits.incrementAndGet();
          exchange.sendResponseHeaders(500, -1);
          exchange.close();
        });

    SourceConnectionTestResult result = connector.testConnection(feed(null), null);

    assertThat(result.message()).isEqualTo("RSS-Feed erreichbar, 1 Eintrag gefunden.");
    assertThat(result.details()).isNull();
    assertThat(detailPageHits.get()).isZero();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> detailPage(SourceConnectionTestResult result) {
    assertThat(result.details()).isNotNull();
    return (Map<String, Object>) result.details().get("detailPage");
  }

  private SourceSettings feed(Map<String, Object> settings) {
    return new SourceSettings(
        null, baseUrl + "/feed.xml", null, settings == null ? null : ConnectorData.of(settings));
  }

  private KnowledgeLibrary library(String feedUrl) {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Feed",
        null,
        UUID.randomUUID(),
        SourceTypes.RSS_FEED,
        null,
        feedUrl,
        null);
  }

  private void serveFeed(String... paths) {
    StringBuilder items = new StringBuilder();
    for (String path : paths) {
      items
          .append("<item><title>Titel</title><link>")
          .append(baseUrl)
          .append(path)
          .append("</link></item>");
    }
    String feed = "<rss version=\"2.0\"><channel><title>Feed</title>" + items + "</channel></rss>";
    server.createContext(
        "/feed.xml",
        exchange -> {
          byte[] bytes = feed.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }

  private void serve(String path, String html) {
    server.createContext(
        path,
        exchange -> {
          byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
  }
}
