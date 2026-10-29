package io.opaa.indexing.source.googledrive;

import static io.opaa.indexing.spi.ConnectorChecks.blankToNull;
import static io.opaa.indexing.spi.ConnectorChecks.fixedSourceUrl;
import static io.opaa.indexing.spi.ConnectorChecks.requireFixedAddress;
import static io.opaa.indexing.spi.ConnectorChecks.unreachable;
import static io.opaa.indexing.spi.ConnectorChecks.validateAtFixedAddress;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.DefaultKey;
import io.opaa.indexing.spi.OriginalAccess;
import io.opaa.indexing.spi.OriginalUnavailableException;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.ServedOriginals;
import io.opaa.indexing.spi.ServiceAccountKeyAuth;
import io.opaa.indexing.spi.SignIn;
import io.opaa.indexing.spi.SourceBrowser;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceConnector;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.indexing.spi.SourceListing;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.ServedContentTypes;
import io.opaa.knowledge.SourceType;
import io.opaa.sourceaccess.ServerAddressRule;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * core signs in with the service account key - the library's own or its profile's - and hands over
 * an access token only; the scopes, the imitated account and the full-sync rhythm are the connector
 * settings ({@link GoogleDriveSettings}). Under a profile only the profile names the imitated
 * account. A changed address, scope or account discards the run state, so the next run is a full
 * sync.
 */
public class GoogleDriveSourceConnector implements SourceConnector, SourceBrowser, OriginalAccess {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("GOOGLE_DRIVE");

  /**
   * The factory default of the full-sync rhythm. The administration may set another for the
   * installation, a library its own ({@code FullSyncRhythm}).
   */
  public static final Duration FULL_SYNC_INTERVAL = Duration.ofDays(7);

  /** The fixed address of the Drive API, the only target of the access token. */
  public static final URI API_BASE = URI.create("https://www.googleapis.com");

  /** The token endpoint of the description, the only target of the signed assertion. */
  public static final URI TOKEN_ENDPOINT = URI.create("https://oauth2.googleapis.com/token");

  public static final String SCOPE = "https://www.googleapis.com/auth/drive.readonly";

  static final String NO_VISIBLE_SCOPE =
      "Das Konto sieht keine geteilte Ablage; ohne imitiertes Konto gehört „Meine Ablage“ nicht"
          + " zum Umfang „Alle Ablagen“.";

  static final String UNAVAILABLE =
      "Google Drive ist derzeit nicht erreichbar. Bitte später erneut versuchen.";

  private static final Logger log = LoggerFactory.getLogger(GoogleDriveSourceConnector.class);

  private static final String SCOPES_STATE = "googleDriveScopes";
  private static final String SUBJECT_STATE = "googleDriveSubject";
  private static final String ALL_SCOPES_STATE = "[*]";
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
            .withDataFlow("source-google-drive")
            .withFullSyncInterval(FULL_SYNC_INTERVAL)
            .withProfiles(
                ProfileDeclaration.of(
                        ConnectionProfileSupport.OPTIONAL,
                        SignIn.serviceAccountKey(new ServiceAccountKeyAuth(tokenEndpoint, SCOPE)))
                    .withAddress(ServerAddressRule.fixed(apiBase.toString()))
                    .withDefaults(
                        DefaultKey.text(
                                GoogleDriveSettings.SUBJECT, "Imitiertes Konto (Delegation)")
                            .onlyOnProfile()));
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
    return fixedSourceUrl(apiBase.toString(), requested);
  }

  @Override
  public String assertionSubject(ConnectorData settings) {
    return GoogleDriveSettings.subjectOf(settings);
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    return validateAtFixedAddress(TYPE, apiBase.toString(), requested, this::readSettings);
  }

  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library, ConnectorData stored) {
    Map<String, Object> state = new LinkedHashMap<>();
    GoogleDriveSettings settings = stored == null ? null : GoogleDriveSettings.stored(stored);
    state.put(
        SCOPES_STATE,
        settings == null
            ? null
            : settings.allScopes()
                ? ALL_SCOPES_STATE
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
    requireFixedAddress(TYPE, apiBase.toString(), settings);
    ConnectorData effective =
        settings.connectorSettings() != null ? settings.connectorSettings() : stored;
    if (blankToNull(settings.sourceCredentials()) == null) {
      return unreachable("Bitte den Dienstkonto-Schlüssel hochladen.");
    }
    if (effective == null) {
      return unreachable("Bitte mindestens einen Bereich wählen.");
    }
    GoogleDriveSettings driveSettings = GoogleDriveSettings.read(effective);
    if (driveSettings.allScopes()) {
      return testAllScopes(settings, driveSettings);
    }
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
          return new SourceConnectionTestResult(
              false, e.getMessage(), null, e.credentialsVerified(), null);
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
    int total = driveSettings.scopes().size();
    boolean all = reachable == total;
    String message;
    if (total == 1) {
      message =
          all
              ? "Anmeldung erfolgreich; der Bereich ist erreichbar."
              : "Der Bereich ist für das Konto nicht sichtbar.";
    } else {
      message =
          all
              ? "Anmeldung erfolgreich; alle " + total + " Bereiche sind erreichbar."
              : (total - reachable)
                  + " von "
                  + total
                  + " Bereichen "
                  + (total - reachable == 1 ? "ist" : "sind")
                  + " für das Konto nicht sichtbar.";
    }
    return new SourceConnectionTestResult(
        all, message, null, true, ConnectorData.of(Map.of("scopes", findings)));
  }

  /** "Alle Ablagen": signs in and counts what the account sees now, as a run would resolve it. */
  private SourceConnectionTestResult testAllScopes(
      SourceSettings settings, GoogleDriveSettings driveSettings) {
    DriveApi api =
        apis.open(
            settings.withSourceUrl(apiBase.toString()),
            settings::sourceCredentials,
            RequestBudget.unbounded());
    List<GoogleDriveScope> found;
    try {
      found = AllDriveScopes.resolve(api, driveSettings.subject());
    } catch (DriveApiException e) {
      boolean verified = e.kind() != DriveApiException.Kind.UNAUTHORIZED;
      return new SourceConnectionTestResult(false, e.getMessage(), null, verified, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Der Verbindungstest wurde unterbrochen.");
    }
    if (found.isEmpty()) {
      return new SourceConnectionTestResult(false, NO_VISIBLE_SCOPE, null, true, null);
    }
    String message =
        "Anmeldung erfolgreich; der Zugang sieht derzeit "
            + (found.size() == 1 ? "1 Ablage" : found.size() + " Ablagen")
            + ".";
    return new SourceConnectionTestResult(true, message, null, true, null);
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
    boolean drivesComplete;
    boolean foldersComplete;
    try {
      drivesComplete =
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
      foldersComplete = collect(api, "files", folders, "files", "folder:", entries);
    } catch (DriveApiException e) {
      return new SourceListing(false, List.of(), e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Auflistung wurde unterbrochen.");
    }
    if (drivesComplete && foldersComplete) {
      return new SourceListing(true, entries, null);
    }
    return new SourceListing(false, entries, cutMessage(drivesComplete, foldersComplete));
  }

  /** Names which of the two lists was cut and, for shared folders, the way to the others. */
  private static String cutMessage(boolean drivesComplete, boolean foldersComplete) {
    String cut =
        drivesComplete
            ? "der freigegebenen Ordner"
            : foldersComplete
                ? "der geteilten Ablagen"
                : "der geteilten Ablagen und der freigegebenen Ordner";
    return "Die Auflistung zeigt nur einen Teil "
        + cut
        + "."
        + (foldersComplete ? "" : " Weitere Ordner lassen sich unten über ihre ID eintragen.");
  }

  /**
   * Adds the entries of up to {@link #MAX_BROWSE_PAGES} pages of {@code path} to {@code entries};
   * {@code false} when a further page is left unread.
   */
  private static boolean collect(
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
        return true;
      }
      token = next.asString();
    }
    return false;
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
        case NOT_FOUND, FORBIDDEN, TOO_LARGE, EXPORT_LIMIT, REDIRECT_REFUSED -> Optional.empty();
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
