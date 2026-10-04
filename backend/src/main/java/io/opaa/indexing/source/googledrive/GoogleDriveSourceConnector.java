package io.opaa.indexing.source.googledrive;

import static io.opaa.indexing.source.ConnectorChecks.blankToNull;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.common.ValidationException;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.OriginalAccess;
import io.opaa.indexing.source.OriginalUnavailableException;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.ServedOriginals;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.ServiceAccountKeyAuth;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.ServedContentTypes;
import io.opaa.knowledge.SourceType;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Google Drive as the source of a library (ADR-0040). The address is fixed ({@code apiBase}); the
 * core signs in with the service account key and hands over an access token only; the scopes, the
 * imitated account and the full-sync rhythm are the connector settings ({@link
 * GoogleDriveSettings}). A changed address, scope or account discards the run state, so the next
 * run is a full sync.
 */
public class GoogleDriveSourceConnector implements SourceConnector, SourceBrowser, OriginalAccess {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("GOOGLE_DRIVE");

  /** The fixed address of the Drive API, the only target of the access token. */
  public static final URI API_BASE = URI.create("https://www.googleapis.com");

  /** The token endpoint of the description, the only target of the signed assertion. */
  public static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");

  public static final String SCOPE = "https://www.googleapis.com/auth/drive.readonly";

  static final String UNAVAILABLE =
      "Google Drive ist derzeit nicht erreichbar. Bitte später erneut versuchen.";

  private static final Logger log = LoggerFactory.getLogger(GoogleDriveSourceConnector.class);

  private static final String SCOPES_STATE = "googleDriveScopes";
  private static final String SUBJECT_STATE = "googleDriveSubject";
  private static final int MAX_BROWSE_PAGES = 10;

  private final URI apiBase;
  private final SourceConnectorDescriptor descriptor;
  private final DriveApiFactory apis;
  private final SourceSyncStateRepository syncStateRepository;

  public GoogleDriveSourceConnector(
      URI apiBase,
      URI tokenEndpoint,
      DriveApiFactory apis,
      SourceSyncStateRepository syncStateRepository) {
    this.apiBase = apiBase;
    this.apis = apis;
    this.syncStateRepository = syncStateRepository;
    this.descriptor =
        SourceConnectorDescriptor.remoteRun(TYPE, "Google Drive")
            .withFullSyncInterval(apis.properties().fullSyncInterval())
            .withProfiles(
                ProfileDeclaration.forbiddenWithServiceAccountKey(
                        new ServiceAccountKeyAuth(tokenEndpoint, SCOPE))
                    .withAddress(ServerAddressRule.fixed(apiBase.toString())));
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Set<String> settingsKeys() {
    return GoogleDriveSettings.KEYS;
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    return GoogleDriveSettings.read(requested).toData();
  }

  @Override
  public String normalizeSourceUrl(String requested) {
    String url = blankToNull(requested);
    if (url == null) {
      return apiBase.toString();
    }
    String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    return trimmed.equals(apiBase.toString()) ? apiBase.toString() : url;
  }

  @Override
  public String assertionSubject(ConnectorData settings) {
    return GoogleDriveSettings.subjectOf(settings);
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    String url = checkConnection(requested);
    if (requested.connectorSettings() == null) {
      throw new ValidationException(
          "sourceSettings sind erforderlich, wenn sourceType " + TYPE + " ist");
    }
    GoogleDriveSettings settings = GoogleDriveSettings.read(requested.connectorSettings());
    return requested.withSourceUrl(url).withConnectorSettings(settings.toData());
  }

  @Override
  public SourceSettings validateChange(
      SourceSettings stored, SourceSettings requested, boolean replacesConnection) {
    if (replacesConnection) {
      ConnectorData effective =
          requested.connectorSettings() != null
              ? requested.connectorSettings()
              : stored.connectorSettings();
      SourceSettings validated = validate(requested.withConnectorSettings(effective));
      return requested.connectorSettings() == null
          ? validated.withConnectorSettings(null)
          : validated;
    }
    if (requested.connectorSettings() != null) {
      return requested.withConnectorSettings(
          GoogleDriveSettings.read(requested.connectorSettings()).toData());
    }
    return requested;
  }

  /** Path, address, TLS switch and proxy of a request; returns the fixed address. */
  private String checkConnection(SourceSettings requested) {
    if (blankToNull(requested.sourcePath()) != null) {
      throw new ValidationException("sourcePath ist für sourceType " + TYPE + " nicht zulässig");
    }
    String url = normalizeSourceUrl(requested.sourceUrl());
    if (!url.equals(apiBase.toString())) {
      throw new ValidationException(
          "sourceUrl ist für sourceType " + TYPE + " fest " + apiBase + " und nicht änderbar");
    }
    if (requested.sourceInsecureSsl()) {
      throw new ValidationException(
          "Die Zertifikatsprüfung lässt sich für sourceType " + TYPE + " nicht abschalten");
    }
    try {
      ProxyAndCredentials.parse(blankToNull(requested.sourceProxy()), null);
    } catch (ProxyAndCredentials.InvalidProxyConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
    return url;
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    library.updateSourceSettings(validated.connectorSettings().toJson());
  }

  @Override
  public void applyChange(
      KnowledgeLibrary library, ConnectorData stored, SourceSettings validated) {
    if (validated.connectorSettings() != null) {
      library.updateSourceSettings(validated.connectorSettings().toJson());
    }
  }

  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library, ConnectorData stored) {
    Map<String, Object> state = new LinkedHashMap<>();
    GoogleDriveSettings settings = stored == null ? null : GoogleDriveSettings.stored(stored);
    state.put(
        SCOPES_STATE,
        settings == null
            ? null
            : settings.scopes().stream().map(GoogleDriveScope::key).toList().toString());
    state.put(SUBJECT_STATE, settings == null ? null : settings.subject());
    return state;
  }

  /** A changed address, scope or imitated account makes the next run a full one from scratch. */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged
        || changedSettings.contains(SCOPES_STATE)
        || changedSettings.contains(SUBJECT_STATE)) {
      syncStateRepository.deleteByLibraryId(library.getId());
    }
  }

  // --- connection test and listing -------------------------------------------------------------

  /**
   * Checks each requested scope (or the stored ones) with the access token the core obtained; the
   * finding per scope is the {@code scopes} detail.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    checkConnection(settings.withSourceUrl(normalizeSourceUrl(settings.sourceUrl())));
    ConnectorData effective =
        settings.connectorSettings() != null ? settings.connectorSettings() : stored;
    if (blankToNull(settings.sourceCredentials()) == null) {
      return unreachable("Bitte den Dienstkonto-Schlüssel hochladen.");
    }
    if (effective == null) {
      return unreachable("Bitte mindestens einen Bereich wählen.");
    }
    GoogleDriveSettings driveSettings = GoogleDriveSettings.read(effective);
    DriveFileStore store =
        new DriveFileStore(
            apis.open(
                settings.withSourceUrl(apiBase.toString()),
                settings::sourceCredentials,
                RequestBudget.unbounded()),
            driveSettings,
            1);
    List<Map<String, Object>> findings = new ArrayList<>();
    int reachable = 0;
    try {
      for (GoogleDriveScope scope : driveSettings.scopes()) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("scope", scope.key());
        try {
          store.requireReachable(scope.container());
          finding.put("reachable", true);
          reachable++;
        } catch (FileAccessException.RunEnding e) {
          return new SourceConnectionTestResult(false, e.getMessage(), null, false, null);
        } catch (FileAccessException e) {
          finding.put("reachable", false);
          finding.put("message", e.getMessage());
        }
        findings.add(finding);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Der Verbindungstest wurde unterbrochen.");
    }
    boolean all = reachable == driveSettings.scopes().size();
    String message =
        all
            ? "Anmeldung erfolgreich; alle " + reachable + " Bereiche sind erreichbar."
            : (driveSettings.scopes().size() - reachable)
                + " von "
                + driveSettings.scopes().size()
                + " Bereichen sind für das Konto nicht sichtbar.";
    return new SourceConnectionTestResult(
        all, message, null, true, ConnectorData.of(Map.of("scopes", findings)));
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine Google-Drive-Bibliothek";
  }

  /**
   * The shared drives and the folders shared with the account, as scope keys; with an imitated
   * account also its own drive.
   */
  @Override
  public SourceListing browse(Query query) {
    SourceSettings settings = query.settings();
    if (blankToNull(settings.sourceCredentials()) == null) {
      throw new ValidationException(
          "sourceCredentials (Dienstkonto-Schlüssel) sind für die Auflistung erforderlich");
    }
    DriveApi api =
        apis.open(
            settings.withSourceUrl(apiBase.toString()),
            settings::sourceCredentials,
            RequestBudget.unbounded());
    List<SourceListing.Entry> entries = new ArrayList<>();
    ConnectorData asked =
        settings.connectorSettings() != null ? settings.connectorSettings() : query.stored();
    if (GoogleDriveSettings.subjectOf(asked) != null) {
      entries.add(new SourceListing.Entry(GoogleDriveScope.MY_DRIVE_KEY, "Meine Ablage"));
    }
    try {
      collect(
          api,
          "drives",
          Map.of("pageSize", "100", "fields", "nextPageToken,drives(id,name)"),
          "drives",
          "drive:",
          entries);
      Map<String, String> folders = new LinkedHashMap<>();
      folders.put(
          "q",
          "mimeType = 'application/vnd.google-apps.folder' and sharedWithMe and trashed = false");
      folders.put("pageSize", "100");
      folders.put("includeItemsFromAllDrives", "true");
      folders.put("supportsAllDrives", "true");
      folders.put("fields", "nextPageToken,files(id,name)");
      collect(api, "files", folders, "files", "folder:", entries);
    } catch (DriveApiException e) {
      return new SourceListing(false, List.of(), e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Auflistung wurde unterbrochen.");
    }
    return new SourceListing(true, entries, null);
  }

  private static void collect(
      DriveApi api,
      String path,
      Map<String, String> query,
      String field,
      String keyPrefix,
      List<SourceListing.Entry> entries)
      throws DriveApiException, InterruptedException {
    String token = null;
    for (int page = 0; page < MAX_BROWSE_PAGES; page++) {
      Map<String, String> paged = new LinkedHashMap<>(query);
      if (token != null) {
        paged.put("pageToken", token);
      }
      JsonNode answer = api.get(path, paged);
      JsonNode items = answer.get(field);
      if (items != null && items.isArray()) {
        for (JsonNode item : items) {
          entries.add(
              new SourceListing.Entry(
                  keyPrefix + item.get("id").asString(),
                  item.get("name") == null ? null : item.get("name").asString()));
        }
      }
      JsonNode next = answer.get("nextPageToken");
      if (next == null || next.isNull()) {
        return;
      }
      token = next.asString();
    }
  }

  // --- original --------------------------------------------------------------------------------

  /**
   * Downloads or exports the file the document names, the way a run fetches it; the temp file is
   * deleted when the served stream is closed. A file Drive does not show is no original; Drive
   * unreachable is {@link OriginalUnavailableException}.
   */
  @Override
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    String filePath = document.getFilePath();
    if (filePath == null || !filePath.startsWith(DriveFileStore.OPEN_PREFIX)) {
      return Optional.empty();
    }
    String id = filePath.substring(DriveFileStore.OPEN_PREFIX.length());
    if (settings.sourceCredentials() == null || !id.matches("[A-Za-z0-9_-]{1,200}")) {
      return Optional.empty();
    }
    DriveApi api =
        apis.open(
            settings.withSourceUrl(apiBase.toString()),
            settings::sourceCredentials,
            RequestBudget.unbounded());
    long bound = apis.properties().maxFileSizeBytes();
    Path file;
    try {
      DriveFile meta =
          DriveFile.of(
              api.get(
                  "files/" + id, Map.of("fields", DriveFile.FIELDS, "supportsAllDrives", "true")));
      GoogleFormats.Export export = GoogleFormats.exportOf(meta.mimeType());
      if (GoogleFormats.isGoogleFile(meta.mimeType()) && export == null) {
        return Optional.empty();
      }
      file =
          export == null
              ? api.download(
                  "files/" + id, Map.of("alt", "media", "supportsAllDrives", "true"), bound)
              : exportOriginal(api, id, export, bound);
      if (file == null) {
        return Optional.empty();
      }
    } catch (DriveApiException e) {
      return switch (e.kind()) {
        case NOT_FOUND, FORBIDDEN, TOO_LARGE, EXPORT_LIMIT -> Optional.empty();
        default ->
            throw new OriginalUnavailableException(
                UNAVAILABLE,
                "Drive file of document " + document.getId() + " is not readable right now",
                e);
      };
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OriginalUnavailableException(
          UNAVAILABLE,
          "Reading the Drive file of document " + document.getId() + " was interrupted",
          e);
    }
    try {
      InputStream stream =
          ServedOriginals.deletingOnClose(Files.newInputStream(file), List.of(file));
      return Optional.of(
          DocumentContent.ofStream(
              stream,
              document.getFileName(),
              ServedContentTypes.forFile(document.getContentType(), file)));
    } catch (IOException e) {
      ServedOriginals.deleteQuietly(file);
      log.warn("Downloaded Drive file of document {} could not be opened", document.getId());
      return Optional.empty();
    }
  }

  private static Path exportOriginal(
      DriveApi api, String id, GoogleFormats.Export export, long bound)
      throws DriveApiException, InterruptedException {
    try {
      return api.download("files/" + id + "/export", Map.of("mimeType", export.mediaType()), bound);
    } catch (DriveApiException e) {
      if (e.kind() != DriveApiException.Kind.EXPORT_LIMIT || !export.textFallback()) {
        throw e;
      }
      return api.download(
          "files/" + id + "/export", Map.of("mimeType", GoogleFormats.TEXT.mediaType()), bound);
    }
  }

  @Override
  public OptionalLong streamedOriginalBound() {
    return OptionalLong.of(apis.properties().maxFileSizeBytes());
  }
}
