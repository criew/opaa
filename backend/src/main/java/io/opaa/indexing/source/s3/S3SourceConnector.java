package io.opaa.indexing.source.s3;

import static io.opaa.indexing.source.ConnectorChecks.blankToNull;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.OriginalAccess;
import io.opaa.indexing.source.OriginalUnavailableException;
import io.opaa.indexing.source.PushIntake;
import io.opaa.indexing.source.PushIntakeHandler;
import io.opaa.indexing.source.ServedOriginals;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.s3.events.S3EventAuthentication;
import io.opaa.indexing.source.s3.events.S3EventService;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.ServedContentTypes;
import io.opaa.s3.S3AccessException;
import io.opaa.s3.S3Connection;
import io.opaa.s3.S3Credentials;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

/**
 * An S3-compatible object store (ADR-0027). {@code sourceUrl} is the endpoint, stored normalised;
 * the credentials are required as {@code accessKey:secretKey[:sessionToken]}; the scopes and
 * patterns are the connector settings, read as {@link S3SourceSettings}. Before anything is stored,
 * the endpoint, the proxy and - under virtual-host addressing - every bucket host pass the target
 * validation (Entscheidung 8). A changed endpoint or changed settings discard the resumption state,
 * so the next run lists every scope from scratch (Entscheidung 3).
 */
public class S3SourceConnector
    implements SourceConnector, SourceBrowser, OriginalAccess, PushIntakeHandler {

  private static final Logger log = LoggerFactory.getLogger(S3SourceConnector.class);

  private static final String SETTINGS_STATE = "s3Settings";

  /** The 503 of a store that cannot be reached; the store's own sentence stays in the log. */
  static final String OBJECT_STORE_UNAVAILABLE =
      "Der Objektspeicher dieser Bibliothek ist derzeit nicht erreichbar. Bitte später erneut"
          + " versuchen.";

  private static final SourceConnectorDescriptor DESCRIPTOR =
      new SourceConnectorDescriptor(
          DocumentSourceType.S3, true, new PushIntake("s3EventsToken", "Ein Ereignis-Token"), null);

  private final S3ConnectionService connectionService;
  private final S3ClientFactory clientFactory;
  private final SourceSyncStateRepository syncStateRepository;
  private final S3OriginalAccess originalAccess;
  private final S3EventService eventService;

  public S3SourceConnector(
      S3ConnectionService connectionService,
      S3ClientFactory clientFactory,
      SourceSyncStateRepository syncStateRepository,
      S3OriginalAccess originalAccess,
      S3EventService eventService) {
    this.connectionService = connectionService;
    this.clientFactory = clientFactory;
    this.syncStateRepository = syncStateRepository;
    this.originalAccess = originalAccess;
    this.eventService = eventService;
  }

  /**
   * Downloads the object from the library's own store, the way a run reads it; the temp file is
   * deleted when the served stream is closed. An object this library resolves to nothing is "no
   * original"; a store that cannot be reached is {@link OriginalUnavailableException}.
   */
  @Override
  public Optional<DocumentContent> openOriginal(Document document, KnowledgeLibrary library) {
    Optional<S3Download> download;
    try {
      download = originalAccess.download(library, document.getFilePath());
    } catch (S3AccessException e) {
      throw new OriginalUnavailableException(
          OBJECT_STORE_UNAVAILABLE,
          "S3 object of document " + document.getId() + " is not readable right now",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OriginalUnavailableException(
          OBJECT_STORE_UNAVAILABLE,
          "Reading the S3 object of document " + document.getId() + " was interrupted",
          e);
    }
    if (download.isEmpty()) {
      return Optional.empty();
    }
    Path file = download.get().file();
    try {
      String contentType = document.getContentType();
      if (contentType == null || contentType.isBlank()) {
        contentType = ServedOriginals.normalizeContentType(download.get().contentType());
      }
      InputStream stream =
          ServedOriginals.deletingOnClose(Files.newInputStream(file), List.of(file));
      return Optional.of(
          DocumentContent.ofStream(
              stream, document.getFileName(), ServedContentTypes.forFile(contentType, file)));
    } catch (IOException e) {
      ServedOriginals.deleteQuietly(file);
      log.warn("Downloaded S3 object of document {} could not be opened", document.getId(), e);
      return Optional.empty();
    }
  }

  @Override
  public OptionalLong streamedOriginalBound() {
    return OptionalLong.of(originalAccess.maxObjectSizeBytes());
  }

  @Override
  public void acceptNotification(UUID libraryId, byte[] body, UnaryOperator<String> header) {
    eventService.accept(
        libraryId,
        body,
        header.apply(HttpHeaders.AUTHORIZATION),
        header.apply(S3EventAuthentication.SHARED_SECRET_HEADER));
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  /** Buckets, prefixes, overlap and patterns are checked here, before anything else is asked. */
  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    return requested == null ? null : S3SourceSettingsJson.toData(settingsOf(requested));
  }

  private static S3SourceSettings settingsOf(ConnectorData data) {
    try {
      return S3SourceSettingsJson.fromData(data);
    } catch (S3Scope.InvalidS3ScopeException
        | S3SourceSettings.InvalidS3SourceSettingsException e) {
      throw new ValidationException("s3Settings: " + e.getMessage());
    }
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    if (requested.sourcePath() != null) {
      throw new ValidationException("sourcePath ist für sourceType S3 nicht zulässig");
    }
    if (requested.sourceUrl() == null) {
      throw new ValidationException(
          "sourceUrl (Endpoint des Objektspeichers) ist erforderlich, wenn sourceType S3 ist");
    }
    String normalizedUrl;
    try {
      normalizedUrl = S3Connection.normalizeEndpoint(requested.sourceUrl()).toString();
    } catch (S3Connection.InvalidEndpointException e) {
      throw new ValidationException(e.getMessage());
    }
    if (requested.sourceCredentials() == null) {
      throw new ValidationException("sourceCredentials sind erforderlich, wenn sourceType S3 ist");
    }
    if (requested.connectorSettings() == null) {
      throw new ValidationException("s3Settings sind erforderlich, wenn sourceType S3 ist");
    }
    S3SourceSettings settings = settingsOf(requested.connectorSettings());
    requireReachableTargets(
        normalizedUrl,
        requested.sourceProxy(),
        requested.sourceInsecureSsl(),
        requested.sourceCredentials(),
        settings);
    return requested
        .withSourceUrl(normalizedUrl)
        .withConnectorSettings(S3SourceSettingsJson.toData(settings));
  }

  /**
   * A new connection is validated against the requested settings, or the stored ones when the
   * request keeps them; settings replaced on their own still pass the target validation against the
   * stored connection (a new bucket host under virtual-host addressing).
   */
  @Override
  public SourceSettings validateChange(
      KnowledgeLibrary library, SourceSettings requested, boolean replacesConnection) {
    if (replacesConnection) {
      ConnectorData effective =
          requested.connectorSettings() != null
              ? requested.connectorSettings()
              : ConnectorData.storedIn(library);
      SourceSettings validated = validate(requested.withConnectorSettings(effective));
      return requested.connectorSettings() == null
          ? validated.withConnectorSettings(null)
          : validated;
    }
    if (requested.connectorSettings() != null) {
      S3SourceSettings settings = settingsOf(requested.connectorSettings());
      requireReachableTargets(
          library.getSourceUrl(),
          library.getSourceProxy(),
          library.isSourceInsecureSsl(),
          library.getSourceCredentials(),
          settings);
      return requested.withConnectorSettings(S3SourceSettingsJson.toData(settings));
    }
    return requested;
  }

  /**
   * The hosts a library will contact pass the target validation when its configuration is saved; a
   * refusal or an unresolvable host is a 400 with the validator's own German message.
   */
  private void requireReachableTargets(
      String normalizedUrl,
      String sourceProxy,
      boolean sourceInsecureSsl,
      String sourceCredentials,
      S3SourceSettings s3Settings) {
    S3Credentials credentials;
    try {
      credentials = S3Credentials.parse(sourceCredentials);
    } catch (S3Credentials.InvalidCredentialsFormatException e) {
      throw new ValidationException(e.getMessage());
    }
    ProxyAndCredentials proxy;
    try {
      proxy = ProxyAndCredentials.parse(sourceProxy, null);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
    S3Connection connection =
        new S3Connection(
            URI.create(normalizedUrl),
            s3Settings.effectiveRegion(),
            s3Settings.pathStyle(),
            credentials,
            proxy.proxyHost(),
            proxy.proxyPort(),
            sourceInsecureSsl);
    try {
      clientFactory.validateTargets(connection, s3Settings.scopes());
    } catch (S3AccessException e) {
      throw new ValidationException(e.getMessage());
    }
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    library.updateSourceSettings(validated.connectorSettings().toJson());
  }

  @Override
  public void applyChange(KnowledgeLibrary library, SourceSettings validated) {
    if (validated.connectorSettings() != null) {
      library.updateSourceSettings(validated.connectorSettings().toJson());
    }
  }

  /**
   * The scopes are the scope every reader sees, and the record carries no credential. A stored
   * document the record no longer accepts is left out rather than failing the whole read.
   */
  @Override
  public ConnectorData settingsView(KnowledgeLibrary library, boolean manager) {
    try {
      S3SourceSettings settings = S3SourceSettingsJson.of(library);
      return settings == null ? null : S3SourceSettingsJson.toData(settings);
    } catch (S3Scope.InvalidS3ScopeException
        | S3SourceSettings.InvalidS3SourceSettingsException e) {
      log.warn(
          "Library {} carries S3 settings the record rejects; omitted from the response: {}",
          library.getId(),
          e.getMessage());
      return null;
    }
  }

  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library) {
    Map<String, Object> state = new HashMap<>();
    state.put(SETTINGS_STATE, S3SourceSettingsJson.write(S3SourceSettingsJson.of(library)));
    return state;
  }

  /** Any settings change counts: discarding the state is safe, keeping a stale one is not. */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged || changedSettings.contains(SETTINGS_STATE)) {
      syncStateRepository.deleteByLibraryId(library.getId());
    }
  }

  /**
   * Probes the requested scopes, or the stored library's when the request names none; the finding
   * per scope is the test's {@code scopes} detail.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    if (blankToNull(settings.sourcePath()) != null) {
      throw new ValidationException("sourcePath ist für sourceType S3 nicht zulässig");
    }
    if (settings.sourceUrl() == null) {
      throw new ValidationException(
          "sourceUrl (Endpoint des Objektspeichers) ist erforderlich, wenn sourceType S3 ist");
    }
    ConnectorData probed =
        settings.connectorSettings() != null ? settings.connectorSettings() : stored;
    S3ConnectionService.Probe probe;
    try {
      probe =
          connectionService.probe(
              settings.sourceUrl(),
              blankToNull(settings.sourceProxy()),
              blankToNull(settings.sourceCredentials()),
              settings.sourceInsecureSsl(),
              probed == null ? null : settingsOf(probed));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Der Verbindungstest wurde unterbrochen.");
    }
    return new SourceConnectionTestResult(
        probe.reachable(),
        probe.message(),
        probe.objectCount(),
        probe.credentialsVerified(),
        ConnectorData.of(
            Map.of("scopes", probe.scopes().stream().map(S3ScopeCheck::toJson).toList())));
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine S3-Bibliothek";
  }

  /**
   * The buckets the key may see; {@code region} and {@code pathStyle} are the query's when given,
   * else the stored library's - a listing against a stored library signs for its configured region.
   */
  @Override
  public SourceListing browse(Query query) {
    SourceSettings settings = query.settings();
    String credentials = blankToNull(settings.sourceCredentials());
    if (credentials == null) {
      throw new ValidationException(
          "sourceCredentials sind für die Bucket-Auflistung erforderlich");
    }
    ConnectorData requested = settings.connectorSettings();
    S3SourceSettings stored = query.stored() == null ? null : settingsOf(query.stored());
    String requestedRegion =
        requested == null || requested.get("region") == null
            ? null
            : blankToNull(requested.get("region").toString());
    String region =
        requestedRegion != null
            ? requestedRegion
            : stored == null ? null : stored.effectiveRegion();
    Object requestedPathStyle = requested == null ? null : requested.get("pathStyle");
    boolean pathStyle =
        requestedPathStyle != null
            ? Boolean.TRUE.equals(requestedPathStyle)
            : stored != null && stored.pathStyle();
    S3BucketListResult result;
    try {
      result =
          connectionService.listBuckets(
              settings.sourceUrl(),
              blankToNull(settings.sourceProxy()),
              credentials,
              settings.sourceInsecureSsl(),
              region,
              pathStyle);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Bucket-Auflistung wurde unterbrochen.");
    }
    return new SourceListing(
        result.permitted(),
        result.buckets().stream().map(name -> new SourceListing.Entry(name, null)).toList(),
        result.message());
  }
}
