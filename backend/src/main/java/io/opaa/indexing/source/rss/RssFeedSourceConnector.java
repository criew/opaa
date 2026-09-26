package io.opaa.indexing.source.rss;

import static io.opaa.indexing.source.ConnectorChecks.REQUEST_TIMEOUT;
import static io.opaa.indexing.source.ConnectorChecks.parseProxyAndCredentials;
import static io.opaa.indexing.source.ConnectorChecks.reachable;
import static io.opaa.indexing.source.ConnectorChecks.requireHttpUrl;
import static io.opaa.indexing.source.ConnectorChecks.translateConnectionError;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.source.ConnectorChecks;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.ProxyAndCredentials;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An RSS 2.0 feed (#474). The connection test fetches and parses the feed the way a run does,
 * bounded by {@code opaa.indexing.rss.max-feed-size-bytes}, and counts at most {@code max-entries}
 * entries - never more than a run would process. A changed feed address discards the
 * conditional-GET state, so the next run fetches the feed in full.
 */
public class RssFeedSourceConnector implements SourceConnector {

  private static final Logger log = LoggerFactory.getLogger(RssFeedSourceConnector.class);

  private static final SourceConnectorDescriptor DESCRIPTOR =
      SourceConnectorDescriptor.runBased(DocumentSourceType.RSS_FEED);

  private final RssFeedParser feedParser;
  private final RssFeedStateRepository feedStateRepository;
  private final TargetAddressValidator targetAddressValidator;
  private final SourceRequestPolicy requestPolicy;
  private final long maxFeedSizeBytes;
  private final int maxFeedEntries;

  public RssFeedSourceConnector(
      RssFeedParser feedParser,
      RssFeedStateRepository feedStateRepository,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      IndexingProperties properties) {
    this.feedParser = feedParser;
    this.feedStateRepository = feedStateRepository;
    this.targetAddressValidator = targetAddressValidator;
    this.requestPolicy = requestPolicy;
    this.maxFeedSizeBytes = properties.rss().maxFeedSizeBytes();
    this.maxFeedEntries = properties.rss().maxEntries();
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    ConnectorChecks.validateUrlBasedConfiguration(
        DocumentSourceType.RSS_FEED, requested.sourcePath(), requested.sourceUrl());
    return requested;
  }

  /**
   * The feed state row only goes away with the library itself (ON DELETE CASCADE); returning to a
   * former address must not find its stale ETag again and end the run in a false 304.
   */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged) {
      feedStateRepository.deleteByLibraryId(library.getId());
    }
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings) {
    String url = requireHttpUrl(DocumentSourceType.RSS_FEED, settings);
    ProxyAndCredentials config = parseProxyAndCredentials(settings);
    HttpClient httpClient =
        SourceHttpClientFactory.buildHttpClient(
            config.proxyHost(), config.proxyPort(), settings.sourceInsecureSsl());
    String authHeader =
        SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());
    Map<String, String> headers = requestPolicy.headers(authHeader);

    try {
      // the proxy decides where the connection goes and is validated like the target itself
      targetAddressValidator.validateHost(config.proxyHost());
      HttpResponse<InputStream> response =
          RedirectFollowingFetcher.sendFollowingRedirects(
              httpClient,
              url,
              REQUEST_TIMEOUT,
              headers,
              targetAddressValidator,
              RedirectFollowingFetcher.RedirectPolicy.DROP_AUTHORIZATION_OFF_ORIGIN);
      try (InputStream body = response.body()) {
        if (response.statusCode() == 401) {
          return unreachable(
              "Die Zugangsdaten wurden vom Server abgelehnt (HTTP 401 Unauthorized).");
        }
        if (response.statusCode() != 200) {
          return unreachable("Der Server antwortete mit HTTP " + response.statusCode() + ".");
        }
        byte[] bytes;
        try {
          bytes = BoundedStreams.readFully(body, maxFeedSizeBytes);
        } catch (BoundedStreams.LimitExceededException e) {
          return unreachable(
              "Der RSS-Feed überschreitet die zulässige Größe von " + maxFeedSizeBytes + " Byte.");
        }
        List<RssFeedEntry> entries;
        try {
          entries = feedParser.parse(new ByteArrayInputStream(bytes));
        } catch (RssFeedParseException e) {
          // already German and user-facing
          return unreachable(e.getMessage());
        }
        int totalEntries = entries.size();
        int countedEntries = Math.min(totalEntries, maxFeedEntries);
        String message =
            "RSS-Feed erreichbar, "
                + countedEntries
                + " "
                + (countedEntries == 1 ? "Eintrag" : "Einträge")
                + " gefunden.";
        if (totalEntries > countedEntries) {
          message +=
              " Der Feed enthält insgesamt "
                  + totalEntries
                  + " Einträge; ein Lauf verarbeitet"
                  + " davon höchstens "
                  + maxFeedEntries
                  + ".";
        }
        return reachable(message, countedEntries);
      }
    } catch (IOException e) {
      log.warn("RSS_FEED source test failed for {}: {}", url, e.getMessage());
      return unreachable(translateConnectionError(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Die Verbindung wurde unterbrochen.");
    }
  }
}
