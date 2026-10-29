package io.opaa.indexing.source.dropbox;

import static io.opaa.indexing.spi.ConnectorChecks.blankToNull;
import static io.opaa.indexing.spi.ConnectorChecks.fixedSourceUrl;
import static io.opaa.indexing.spi.ConnectorChecks.requireFixedAddress;
import static io.opaa.indexing.spi.ConnectorChecks.unreachable;
import static io.opaa.indexing.spi.ConnectorChecks.validateAtFixedAddress;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.spi.ClientAuthentication;
import io.opaa.indexing.spi.ConnectorChecks;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.DefaultKey;
import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.OAuthAuth;
import io.opaa.indexing.spi.OriginalAccess;
import io.opaa.indexing.spi.OriginalUnavailableException;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.Revocation;
import io.opaa.indexing.spi.SecretKind;
import io.opaa.indexing.spi.ServedOriginals;
import io.opaa.indexing.spi.SignIn;
import io.opaa.indexing.spi.SourceBrowser;
import io.opaa.indexing.spi.SourceConnectionTestResult;
import io.opaa.indexing.spi.SourceConnector;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.indexing.spi.SourceCredentialsException;
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
 * Dropbox as the source of a library. Dropbox grants access only through OAuth with a refresh
 * token, so a library always runs on a profile whose registration is the operator's Dropbox app;
 * the library's own consent ("Quelle verbinden") lies in the token store, and the connector gets
 * the access token alone. The address is fixed (the API host); the folders, the team space and the
 * full-sync rhythm are the connector settings ({@link DropboxSettings}). A profile marked as a team
 * app ({@code teamApp}) asks a team admin's consent with the team scopes; its libraries read team
 * folders by namespace as that admin, never a member's folder. A changed folder list, team space or
 * team folder list discards the run state, so the next run is a full sync.
 */
public class DropboxSourceConnector implements SourceConnector, SourceBrowser, OriginalAccess {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("DROPBOX");

  /**
   * The factory default of the full-sync rhythm. The administration may set another for the
   * installation, a library its own ({@code FullSyncRhythm}).
   */
  public static final Duration FULL_SYNC_INTERVAL = Duration.ofDays(7);

  /** The permissions the Dropbox app needs; nothing it could write with. */
  public static final String SCOPES = "account_info.read files.metadata.read files.content.read";

  /**
   * The permissions a team app needs: the member scopes for reading as the admin, the team scopes
   * for the namespaces, the consenting admin and the team folders; nothing it could write with.
   */
  public static final String TEAM_SCOPES =
      SCOPES + " files.team_metadata.read team_data.member team_info.read";

  static final String UNAVAILABLE =
      "Dropbox ist derzeit nicht erreichbar. Bitte später erneut versuchen.";

  private static final Logger log = LoggerFactory.getLogger(DropboxSourceConnector.class);

  private static final String FOLDERS_STATE = "dropboxFolders";
  private static final String TEAM_SPACE_STATE = "dropboxTeamSpace";
  private static final String TEAM_FOLDERS_STATE = "dropboxTeamFolders";
  private static final int MAX_BROWSE_PAGES = 10;

  private final DropboxApiFactory apis;
  private final SourceSyncStateRepository syncStateRepository;
  private final SourceConnectorDescriptor descriptor;
  private final String apiBase;

  public DropboxSourceConnector(
      DropboxApiFactory apis, SourceSyncStateRepository syncStateRepository) {
    this.apis = apis;
    this.syncStateRepository = syncStateRepository;
    DropboxProperties properties = apis.properties();
    this.apiBase = properties.apiBase().toString();
    this.descriptor =
        SourceConnectorDescriptor.remoteRun(TYPE, "Dropbox")
            .withDataFlow("source-dropbox")
            .withoutDeepLink()
            .withFullSyncInterval(FULL_SYNC_INTERVAL)
            .withProfiles(
                ProfileDeclaration.of(
                        ConnectionProfileSupport.REQUIRED,
                        SignIn.oauth(
                            new OAuthAuth(
                                new Endpoint.Fixed(properties.authorizationEndpoint()),
                                new Endpoint.Fixed(properties.tokenEndpoint()),
                                new Revocation.BearerPost(
                                    new Endpoint.Fixed(properties.revocationEndpoint())),
                                SCOPES,
                                Map.of("token_access_type", "offline"),
                                ClientAuthentication.CLIENT_SECRET_BASIC),
                            ConnectionOwnership.LIBRARY))
                    .withAddress(ServerAddressRule.fixed(apiBase))
                    .withDefaults(
                        DefaultKey.bool(
                                DropboxSettings.TEAM_APP,
                                "Team-App (Zustimmung einer Team-Administration)")
                            .onlyOnProfile()));
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Set<String> settingsKeys() {
    return DropboxSettings.KEYS;
  }

  /** A team app asks for the team scopes, unless the profile names its own. */
  @Override
  public String signInScopes(ConnectorData profileDefaults) {
    return DropboxSettings.teamAppOf(profileDefaults) ? TEAM_SCOPES : null;
  }

  /**
   * Own scopes of a team app hold every team scope: without them Dropbox takes no consent as a team
   * app's, and no reconnect helps.
   */
  @Override
  public void checkProfileScopes(String profileScopes, ConnectorData profileDefaults) {
    if (!DropboxSettings.teamAppOf(profileDefaults)) {
      return;
    }
    Set<String> named = Set.of(profileScopes.trim().split("\\s+"));
    List<String> missing =
        java.util.Arrays.stream(TEAM_SCOPES.split(" "))
            .filter(scope -> !named.contains(scope))
            .toList();
    if (!missing.isEmpty()) {
      throw new ValidationException(
          "Eine Team-App braucht die Scopes "
              + String.join(", ", missing)
              + "; „Scopes“ leer lassen oder ergänzen");
    }
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    return DropboxSettings.read(requested).toData();
  }

  @Override
  public String normalizeSourceUrl(String requested) {
    return fixedSourceUrl(apiBase, requested);
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    SourceSettings validated = validateAtFixedAddress(TYPE, apiBase, requested, this::readSettings);
    DropboxSettings.effective(validated.connectorSettings());
    return validated;
  }

  /** The default change rules, settings read with team folders only through a team app. */
  @Override
  public SourceSettings validateChange(
      SourceSettings stored, SourceSettings requested, boolean replacesConnection) {
    if (replacesConnection) {
      return ConnectorChecks.validateReplacement(this::validate, stored, requested);
    }
    return requested.connectorSettings() == null
        ? requested
        : requested.withConnectorSettings(
            DropboxSettings.effective(requested.connectorSettings()).toData());
  }

  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library, ConnectorData stored) {
    Map<String, Object> state = new LinkedHashMap<>();
    DropboxSettings settings = stored == null ? null : DropboxSettings.read(stored);
    state.put(FOLDERS_STATE, settings == null ? null : settings.folders().toString());
    state.put(TEAM_SPACE_STATE, settings == null ? null : settings.teamSpace());
    state.put(
        TEAM_FOLDERS_STATE,
        settings == null || !settings.readsTeamFolders()
            ? null
            : settings.containerKeys().toString());
    return state;
  }

  /** A changed folder list, team space or team folder list makes the next run a full one. */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged
        || changedSettings.contains(FOLDERS_STATE)
        || changedSettings.contains(TEAM_SPACE_STATE)
        || changedSettings.contains(TEAM_FOLDERS_STATE)) {
      syncStateRepository.deleteByLibraryId(library.getId());
    }
  }

  /**
   * The e-mail address of the account the access token was issued to - under a team app the admin's
   * who consented.
   *
   * @throws SourceCredentialsException when Dropbox does not answer the lookup
   */
  @Override
  public Optional<String> connectedAccount(SourceSettings settings) {
    String token = tokenOf(settings);
    if (token == null) {
      return Optional.empty();
    }
    try {
      DropboxApi api = apis.open(settings, () -> token, RequestBudget.unbounded());
      JsonNode account =
          DropboxSettings.teamAppOf(settings.connectorSettings())
              ? DropboxTeam.admin(api)
              : api.rpc("users/get_current_account", null);
      return Optional.ofNullable(DropboxFileStore.text(account, "email"));
    } catch (DropboxApiException e) {
      throw new SourceCredentialsException(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  // --- connection test and listing -------------------------------------------------------------

  /**
   * Checks each requested folder or team folder (or the stored ones) with the access token of the
   * library's consent; the finding per folder is the {@code folders} detail.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    requireFixedAddress(TYPE, apiBase, settings);
    ConnectorData effective =
        settings.connectorSettings() != null ? settings.connectorSettings() : stored;
    String token = tokenOf(settings);
    if (token == null) {
      return unreachable("Bitte zuerst die Quelle verbinden.");
    }
    if (effective == null) {
      return unreachable("Bitte mindestens einen Ordner angeben.");
    }
    DropboxSettings dropboxSettings = DropboxSettings.effective(effective);
    boolean team = dropboxSettings.readsTeamFolders();
    DropboxFileStore store =
        new DropboxFileStore(
            apis.open(settings, () -> token, RequestBudget.unbounded()), dropboxSettings, 1);
    List<Map<String, Object>> findings = new ArrayList<>();
    int reachable = 0;
    try {
      for (FileContainer container : store.containers()) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("folder", container.key());
        if (team) {
          dropboxSettings.teamFolders().stream()
              .filter(folder -> folder.id().equals(container.key()))
              .findFirst()
              .ifPresent(folder -> finding.put("name", folder.label()));
        }
        try {
          store.requireReachable(container);
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
    int total = findings.size();
    boolean all = reachable == total;
    String one = team ? "der Team-Ordner" : "der Ordner";
    String many = team ? "Team-Ordnern" : "Ordnern";
    String unseen = team ? "nicht lesbar" : "für das verbundene Konto nicht sichtbar";
    String message;
    if (total == 1) {
      message =
          all
              ? "Verbindung erfolgreich; " + one + " ist erreichbar."
              : (team ? "Der Team-Ordner" : "Der Ordner") + " ist " + unseen + ".";
    } else {
      message =
          all
              ? "Verbindung erfolgreich; alle "
                  + total
                  + " "
                  + (team ? "Team-Ordner" : "Ordner")
                  + " sind erreichbar."
              : (total - reachable)
                  + " von "
                  + total
                  + " "
                  + many
                  + " "
                  + (total - reachable == 1 ? "ist" : "sind")
                  + " "
                  + unseen
                  + ".";
    }
    return new SourceConnectionTestResult(
        all, message, null, true, ConnectorData.of(Map.of("folders", findings)));
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine Dropbox-Bibliothek";
  }

  /**
   * The folders directly below {@code path} of the listing's parameters ({@code /} by default), in
   * the team space where {@code teamSpace} asks for it; the keys are their paths. Under a team app
   * the team folders of the team, keyed by namespace id.
   */
  @Override
  public SourceListing browse(Query query) {
    SourceSettings settings = query.settings();
    String token = tokenOf(settings);
    if (token == null) {
      throw new ValidationException("Für die Ordnerauswahl muss die Quelle verbunden sein");
    }
    ConnectorData asked =
        settings.connectorSettings() == null
            ? ConnectorData.of(Map.of())
            : settings.connectorSettings();
    DropboxApi api = apis.open(settings, () -> token, RequestBudget.unbounded());
    if (DropboxSettings.teamAppOf(asked)) {
      return teamFolders(api);
    }
    String path =
        asked.get("path") instanceof String text && !text.isBlank()
            ? DropboxSettings.normalize(text)
            : "/";
    boolean teamSpace =
        Boolean.TRUE.equals(asked.get(DropboxSettings.TEAM_SPACE))
            || (asked.get(DropboxSettings.TEAM_SPACE) == null
                && DropboxSettings.teamSpaceOf(query.stored()));
    List<SourceListing.Entry> entries = new ArrayList<>();
    try {
      if (teamSpace) {
        String namespace = DropboxFileStore.teamSpaceOf(api.rpc("users/get_current_account", null));
        if (namespace == null) {
          return new SourceListing(
              false,
              List.of(),
              "Das verbundene Dropbox-Konto gehört zu keinem Team mit Team-Bereich.");
        }
        api.rootAt(namespace);
      }
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("path", DropboxSettings.apiPath(path));
      args.put("recursive", false);
      args.put("limit", 2000);
      JsonNode answer = api.rpc("files/list_folder", args);
      for (int page = 1; ; page++) {
        JsonNode items = answer.get("entries");
        if (items != null && items.isArray()) {
          for (JsonNode item : items) {
            if ("folder".equals(DropboxFileStore.text(item, ".tag"))) {
              String display = DropboxFileStore.text(item, "path_display");
              entries.add(
                  new SourceListing.Entry(
                      display == null ? DropboxFileStore.text(item, "path_lower") : display,
                      DropboxFileStore.text(item, "name")));
            }
          }
        }
        JsonNode more = answer.get("has_more");
        if (more == null || !more.asBoolean()) {
          break;
        }
        if (page >= MAX_BROWSE_PAGES) {
          return SourceListing.cutAt(
              entries, "Ordner", "Weitere Ordner lassen sich über ihren Pfad angeben.");
        }
        answer =
            api.rpc(
                "files/list_folder/continue",
                Map.of("cursor", DropboxFileStore.text(answer, "cursor")));
      }
    } catch (DropboxApiException e) {
      return new SourceListing(false, List.of(), e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Auflistung wurde unterbrochen.");
    }
    return new SourceListing(true, entries, null);
  }

  /** The team folders among the team's namespaces, the keys their namespace ids. */
  private static SourceListing teamFolders(DropboxApi api) {
    try {
      DropboxTeam.TeamFolders listed = DropboxTeam.teamFolders(api, Set.of(), MAX_BROWSE_PAGES);
      List<SourceListing.Entry> entries =
          listed.folders().stream()
              .map(folder -> new SourceListing.Entry(folder.id(), folder.name()))
              .toList();
      return listed.complete()
          ? new SourceListing(true, entries, null)
          : SourceListing.cutAt(
              entries,
              "Team-Ordner",
              "Weitere Team-Ordner lassen sich über ihre Namespace-ID angeben.");
    } catch (DropboxApiException e) {
      return new SourceListing(false, List.of(), e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Auflistung wurde unterbrochen.");
    }
  }

  // --- original --------------------------------------------------------------------------------

  /**
   * Downloads or exports the file the document names, the way a run fetches it; the temp file is
   * deleted when the served stream is closed. A file outside the library's folders or team folders,
   * or one Dropbox no longer shows, is no original; Dropbox unreachable is {@link
   * OriginalUnavailableException}.
   */
  @Override
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    String filePath = document.getFilePath();
    String token = tokenOf(settings);
    if (filePath == null
        || !filePath.startsWith(DropboxFileStore.IDENTITY_PREFIX)
        || token == null) {
      return Optional.empty();
    }
    DropboxSettings dropboxSettings;
    try {
      dropboxSettings = DropboxSettings.stored(settings.connectorSettings());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    if (dropboxSettings == null) {
      return Optional.empty();
    }
    Optional<Located> located =
        locate(filePath.substring(DropboxFileStore.IDENTITY_PREFIX.length()), dropboxSettings);
    if (located.isEmpty()) {
      return Optional.empty();
    }
    DropboxApi api = apis.open(settings, () -> token, RequestBudget.unbounded());
    DropboxFileStore store = new DropboxFileStore(api, dropboxSettings, 1);
    long bound = apis.properties().maxFileSizeBytes();
    Path file;
    try {
      var entry = store.head(new FileContainer(located.get().container()), located.get().path());
      if (entry.exclusion() != null) {
        return Optional.empty();
      }
      file = store.fetch(entry, bound).file();
    } catch (FileAccessException.Gone
        | FileAccessException.Unreadable
        | FileAccessException.TooLarge
        | FileAccessException.ContainerUnlistable e) {
      return Optional.empty();
    } catch (FileAccessException e) {
      throw new OriginalUnavailableException(
          UNAVAILABLE,
          "Dropbox file of document " + document.getId() + " is not readable right now",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OriginalUnavailableException(
          UNAVAILABLE,
          "Reading the Dropbox file of document " + document.getId() + " was interrupted",
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
      log.warn("Downloaded Dropbox file of document {} could not be opened", document.getId());
      return Optional.empty();
    }
  }

  /** A document's file: the container that holds it and its lower-case path there. */
  private record Located(String container, String path) {}

  /**
   * The container of {@code identity} - the path after the identity prefix - among {@code
   * settings}; empty when no chosen folder or team folder holds it.
   */
  private static Optional<Located> locate(String identity, DropboxSettings settings) {
    if (settings.readsTeamFolders()) {
      if (!identity.startsWith(DropboxFileStore.TEAM_FOLDER_PREFIX)) {
        return Optional.empty();
      }
      String rest = identity.substring(DropboxFileStore.TEAM_FOLDER_PREFIX.length());
      int slash = rest.indexOf('/');
      if (slash <= 0) {
        return Optional.empty();
      }
      String namespace = rest.substring(0, slash);
      return settings.containerKeys().contains(namespace)
          ? Optional.of(new Located(namespace, rest.substring(slash)))
          : Optional.empty();
    }
    return settings.folders().stream()
        .filter(folder -> DropboxSettings.contains(folder, identity))
        .findFirst()
        .map(folder -> new Located(folder, identity));
  }

  @Override
  public OptionalLong streamedOriginalBound() {
    return OptionalLong.of(apis.properties().maxFileSizeBytes());
  }

  /** The access token among {@code settings}, {@code null} for none or another kind of secret. */
  private static String tokenOf(SourceSettings settings) {
    var secret = settings.credentials();
    return secret == null || secret.kind() != SecretKind.ACCESS_TOKEN
        ? null
        : blankToNull(secret.value());
  }
}
