package io.opaa.indexing.source.rss;

import static io.opaa.indexing.spi.ConnectorChecks.REQUEST_TIMEOUT;
import static io.opaa.indexing.spi.ConnectorChecks.basicCredentials;
import static io.opaa.indexing.spi.ConnectorChecks.reachable;
import static io.opaa.indexing.spi.ConnectorChecks.requireHttpUrl;
import static io.opaa.indexing.spi.ConnectorChecks.translateConnectionError;
import static io.opaa.indexing.spi.ConnectorChecks.unreachable;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.compliance.DataFlow;
import io.opaa.format.DocumentProperties;
import io.opaa.indexing.spi.ConnectorChecks;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.OriginalAccess;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.RemoteOriginalAccess;
import io.opaa.indexing.spi.RowDeclaredProperties;
import io.opaa.indexing.spi.SignIn;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceConnector;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BasicCredentials;
import io.opaa.sourceaccess.BoundedStreams;
import io.opaa.sourceaccess.RateLimitListener;
import io.opaa.sourceaccess.RedirectFollowingFetcher;
import io.opaa.sourceaccess.SourceHttpClientFactory;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An RSS 2.0 feed. The connection test fetches and parses the feed the way a run does, bounded by
 * {@code opaa.indexing.rss.max-feed-size-bytes}, and counts at most {@code max-entries} entries -
 * never more than a run would process. A changed feed address discards the conditional-GET state,
 * so the next run fetches the feed in full. The original of an indexed entry is streamed from its
 * stored URL ({@link RemoteOriginalAccess}).
 */
@DataFlow("source-rss")
public class RssFeedSourceConnector
    implements SourceConnector, OriginalAccess, RowDeclaredProperties {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("RSS_FEED");

  private static final Logger log = LoggerFactory.getLogger(RssFeedSourceConnector.class);

  /** What a required profile leaves open: the detail pages an entry links to. */
  static final String REQUIREMENT_GAP =
      "Die Pflicht legt nur die Feed-Adresse fest. Die Detailseiten eines Feeds stammen aus seinen"
          + " Einträgen und können auf fremden Servern liegen; sie werden weiter abgerufen, aber"
          + " ohne Zugangsdaten.";

  private static final SourceConnectorDescriptor DESCRIPTOR =
      SourceConnectorDescriptor.remoteRun(TYPE, "RSS-Feed")
          .withDataFlow("source-rss")
          .withProfiles(
              ProfileDeclaration.of(
                      ConnectionProfileSupport.OPTIONAL,
                      SignIn.of(ConnectionAuthMethod.NONE, ConnectionOwnership.LIBRARY),
                      SignIn.personalSecret(
                          PersonalSecretForm.USERNAME_AND_PASSWORD, ConnectionOwnership.LIBRARY))
                  .withRequirementGap(REQUIREMENT_GAP));

  private final RssFeedParser feedParser;
  private final RssFeedStateRepository feedStateRepository;
  private final TargetAddressValidator targetAddressValidator;
  private final SourceRequestPolicy requestPolicy;
  private final long maxFeedSizeBytes;
  private final int maxFeedEntries;
  private final RemoteOriginalAccess remoteOriginals;
  private final DetailPageExtractor detailPageExtractor;

  public RssFeedSourceConnector(
      RssFeedParser feedParser,
      RssFeedStateRepository feedStateRepository,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      RssFeedProperties properties,
      RemoteOriginalAccess remoteOriginals) {
    this.feedParser = feedParser;
    this.feedStateRepository = feedStateRepository;
    this.targetAddressValidator = targetAddressValidator;
    this.requestPolicy = requestPolicy;
    this.maxFeedSizeBytes = properties.maxFeedSizeBytes();
    this.maxFeedEntries = properties.maxEntries();
    this.remoteOriginals = remoteOriginals;
    this.detailPageExtractor =
        new DetailPageExtractor(targetAddressValidator, properties, requestPolicy);
  }

  @Override
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    return remoteOriginals.openOriginal(document, library, settings);
  }

  @Override
  public OptionalLong streamedOriginalBound() {
    return remoteOriginals.streamedOriginalBound();
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  /** Content selector and attachment profile ({@link RssFeedSettings}), each optional. */
  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    return RssFeedSettings.read(requested).toData();
  }

  /** Readers see how the detail pages are read, as the row „Umfang“ shows it to them. */
  @Override
  public ConnectorData settingsView(
      KnowledgeLibrary library, ConnectorData stored, boolean manager) {
    return stored;
  }

  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library, ConnectorData stored) {
    RssFeedSettings settings = RssFeedSettings.read(stored);
    Map<String, Object> state = new LinkedHashMap<>();
    state.put(RssFeedSettings.MAIN_CONTENT_SELECTOR, settings.mainContentSelector());
    state.put(
        RssFeedSettings.ATTACHMENT_PROFILE,
        settings.attachmentProfile() == null ? null : settings.attachmentProfile().name());
    return state;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    ConnectorChecks.validateUrlBasedConfiguration(
        RssFeedSourceConnector.TYPE, requested.sourcePath(), requested.sourceUrl());
    return requested;
  }

  /**
   * The feed state row only goes away with the library itself (ON DELETE CASCADE); returning to a
   * former address must not find its stale ETag again and end the run in a false 304. Changed
   * extraction settings leave a stored state as it is - it names the settings it was saved under -
   * and without one leave {@link RssFeedState#UNKNOWN_EXTRACTION}, so the next run reprocesses.
   */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged) {
      feedStateRepository.deleteByLibraryId(library.getId());
    }
    boolean extractionChanged =
        changedSettings.contains(RssFeedSettings.MAIN_CONTENT_SELECTOR)
            || changedSettings.contains(RssFeedSettings.ATTACHMENT_PROFILE);
    String feedUrl = library.getSourceUrl();
    if (!extractionChanged || feedUrl == null) {
      return;
    }
    if (addressChanged
        || feedStateRepository.findByLibraryIdAndFeedUrl(library.getId(), feedUrl).isEmpty()) {
      RssFeedState unknown = new RssFeedState(library.getId(), feedUrl, null, null);
      unknown.setExtractionSettings(RssFeedState.UNKNOWN_EXTRACTION);
      feedStateRepository.save(unknown);
    }
  }

  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    String url = requireHttpUrl(RssFeedSourceConnector.TYPE, settings);
    BasicCredentials config = basicCredentials(settings);
    HttpClient httpClient = SourceHttpClientFactory.buildHttpClient();
    String authHeader =
        SourceHttpClientFactory.buildAuthHeader(config.username(), config.password());
    Map<String, String> headers = requestPolicy.headers(authHeader);

    try {
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
          return unreachable(ConnectorChecks.unexpectedStatus(response.statusCode()));
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
        RssFeedSettings extraction =
            RssFeedSettings.read(
                settings.connectorSettings() != null ? settings.connectorSettings() : stored);
        if (extraction.isFactoryDefault() || entries.isEmpty()) {
          return reachable(message, countedEntries);
        }
        DetailPageCheck check =
            checkDetailPage(httpClient, url, authHeader, entries.getFirst().link(), extraction);
        return new SourceConnectionTestResult(
            true, message + " " + check.message(), (long) countedEntries, null, check.details());
      }
    } catch (IOException e) {
      log.warn("RSS_FEED source test failed for {}: {}", url, e.getMessage());
      return unreachable(translateConnectionError(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Die Verbindung wurde unterbrochen.");
    }
  }

  /** What the test found on the first entry's detail page, as sentence and as findings. */
  private record DetailPageCheck(String message, ConnectorData details) {

    static DetailPageCheck unchecked(String reason) {
      return new DetailPageCheck(
          "Die Detailseite des ersten Eintrags konnte nicht geprüft werden: " + reason,
          ConnectorData.of(Map.of("detailPage", Map.of("checked", false))));
    }
  }

  /**
   * Reduces the first entry's detail page the way a run would under {@code extraction} and reports
   * what the selector and the profile find there. Credentials go only to the feed's own origin, as
   * in a run; a page that cannot be read leaves the feed reachable and only says so.
   */
  private DetailPageCheck checkDetailPage(
      HttpClient httpClient,
      String feedUrl,
      String authHeader,
      String entryUrl,
      RssFeedSettings extraction)
      throws InterruptedException {
    DetailPageExtractor.DetailPage page;
    try {
      String entryAuth =
          authHeader != null
                  && RedirectFollowingFetcher.sameOrigin(URI.create(feedUrl), URI.create(entryUrl))
              ? authHeader
              : null;
      page =
          detailPageExtractor.fetch(
              httpClient, entryUrl, entryAuth, RateLimitListener.NONE, extraction);
    } catch (DetailPageExtractor.RejectedByRemoteException e) {
      return DetailPageCheck.unchecked(e.userMessage() + ".");
    } catch (DetailPageExtractor.UnsupportedContentTypeException e) {
      return DetailPageCheck.unchecked("Ihr Inhaltstyp wird nicht unterstützt.");
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      return DetailPageCheck.unchecked(e.getMessage());
    } catch (IOException e) {
      log.warn("RSS_FEED detail page check failed for {}: {}", entryUrl, e.getMessage());
      return DetailPageCheck.unchecked(translateConnectionError(e));
    } catch (IllegalArgumentException e) {
      return DetailPageCheck.unchecked("Ihre Adresse ist ungültig.");
    }
    String found =
        page.selectorMatched()
            ? "Der Inhaltsselektor findet auf der Detailseite des ersten Eintrags "
                + page.contentAreas()
                + (page.contentAreas() == 1 ? " Bereich" : " Bereiche")
            : "Der Inhaltsselektor findet auf der Detailseite des ersten Eintrags nichts; ein Lauf"
                + " nimmt dort die ganze Seite";
    int attachments = page.attachments().size();
    String message =
        found
            + ", "
            + attachments
            + (attachments == 1 ? " Anlage" : " Anlagen")
            + " nach dem Anlagenprofil "
            + extraction.effectiveProfile().name()
            + ".";
    Map<String, Object> findings = new LinkedHashMap<>();
    findings.put("checked", true);
    findings.put("selectorMatched", page.selectorMatched());
    findings.put("contentAreas", page.contentAreas());
    findings.put("attachments", attachments);
    return new DetailPageCheck(message, ConnectorData.of(Map.of("detailPage", findings)));
  }

  /**
   * An entry's own body was never a file: its declared properties are the stored headline and the
   * feed's publication instant, exactly what the ingest hands the extraction. "Has a headline" is
   * approximated as {@code file_name != file_path}; the name is marked synthetic as the ingest
   * marks it.
   */
  @Override
  public DocumentProperties declaredProperties(Document document) {
    boolean hasHeadline =
        document.getFileName() != null && !document.getFileName().equals(document.getFilePath());
    return DocumentProperties.EMPTY
        .withTitle(hasHeadline ? document.getFileName() : null)
        .withSyntheticName(true)
        .withDocumentDate(DocumentProperties.instantToLocalDate(document.getLastModifiedRemote()));
  }
}
