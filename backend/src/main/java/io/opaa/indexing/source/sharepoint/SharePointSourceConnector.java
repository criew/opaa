package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.source.ConnectorChecks.blankToNull;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.ClientCredentialsAuth;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OriginalAccess;
import io.opaa.indexing.source.OriginalUnavailableException;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.ServedOriginals;
import io.opaa.indexing.source.ServerAddressRule;
import io.opaa.indexing.source.SignIn;
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
import io.opaa.msgraph.GraphClient;
import io.opaa.msgraph.GraphException;
import io.opaa.msgraph.GraphPage;
import io.opaa.sourceaccess.ProxyAndCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * SharePoint document libraries as the source of a library (ADR-0040, Nachtrag „SharePoint“). The
 * address is fixed ({@code apiBase}); only a profile signs in, with the client credentials of an
 * app registration in Entra, and the connector sees the access token alone. The libraries, their
 * optional folders and the full-sync rhythm are the connector settings ({@link
 * SharePointSettings}); a changed address or selection discards the run state. Documents carry no
 * deep link - the original comes through Graph.
 */
public class SharePointSourceConnector implements SourceConnector, SourceBrowser, OriginalAccess {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("SHAREPOINT");

  /** The fixed address of Microsoft Graph, the only target of the access token. */
  public static final URI API_BASE = URI.create("https://graph.microsoft.com");

  /** The token endpoint of Entra, completed with the profile's tenant. */
  public static final String TOKEN_TEMPLATE =
      "https://login.microsoftonline.com/" + Endpoint.WithTenant.PLACEHOLDER + "/oauth2/v2.0/token";

  /** The scope of an app-only token: every application permission granted to the registration. */
  public static final String SCOPE = "https://graph.microsoft.com/.default";

  static final String UNAVAILABLE =
      "SharePoint ist derzeit nicht erreichbar. Bitte später erneut versuchen.";

  private static final Logger log = LoggerFactory.getLogger(SharePointSourceConnector.class);

  private static final String LIBRARIES_STATE = "sharePointLibraries";
  private static final int MAX_BROWSE_PAGES = 10;
  private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
  private static final Pattern SITE_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252},[0-9A-Fa-f-]{36},[0-9A-Fa-f-]{36}");

  private final URI apiBase;
  private final SourceConnectorDescriptor descriptor;
  private final GraphConnections graphs;
  private final SourceSyncStateRepository syncStateRepository;

  SharePointSourceConnector(
      URI apiBase,
      String tokenTemplate,
      GraphConnections graphs,
      SourceSyncStateRepository syncStateRepository) {
    this.apiBase = apiBase;
    this.graphs = graphs;
    this.syncStateRepository = syncStateRepository;
    this.descriptor =
        SourceConnectorDescriptor.remoteRun(TYPE, "SharePoint")
            .withoutDeepLink()
            .withFullSyncInterval(graphs.properties().fullSyncInterval())
            .withProfiles(
                ProfileDeclaration.of(
                        ConnectionProfileSupport.REQUIRED,
                        SignIn.clientCredentials(
                            new ClientCredentialsAuth(
                                new Endpoint.WithTenant(tokenTemplate),
                                SCOPE,
                                ClientAuthentication.CLIENT_SECRET_POST)))
                    .withAddress(ServerAddressRule.fixed(apiBase.toString())));
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Set<String> settingsKeys() {
    return SharePointSettings.KEYS;
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    return SharePointSettings.read(requested).toData();
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
  public SourceSettings validate(SourceSettings requested) {
    String url = checkConnection(requested);
    if (requested.connectorSettings() == null) {
      throw new ValidationException(
          "sourceSettings sind erforderlich, wenn sourceType " + TYPE + " ist");
    }
    SharePointSettings settings = SharePointSettings.read(requested.connectorSettings());
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
          SharePointSettings.read(requested.connectorSettings()).toData());
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
    SharePointSettings settings = stored == null ? null : SharePointSettings.stored(stored);
    Map<String, Object> state = new LinkedHashMap<>();
    state.put(
        LIBRARIES_STATE,
        settings == null
            ? null
            : settings.libraries().stream().map(SharePointLibrary::coverage).toList().toString());
    return state;
  }

  /** A changed address or selection makes the next run a full one from scratch. */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged || changedSettings.contains(LIBRARIES_STATE)) {
      syncStateRepository.deleteByLibraryId(library.getId());
    }
  }

  // --- connection test and listing -------------------------------------------------------------

  /**
   * Checks each requested library (or the stored ones) with the access token the core obtained; the
   * finding per library is the {@code libraries} detail.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    checkConnection(settings.withSourceUrl(normalizeSourceUrl(settings.sourceUrl())));
    ConnectorData effective =
        settings.connectorSettings() != null ? settings.connectorSettings() : stored;
    if (blankToNull(settings.sourceCredentials()) == null) {
      return unreachable("Bitte einen Zugang mit App-Registrierung wählen.");
    }
    if (effective == null) {
      return unreachable("Bitte mindestens eine Dokumentbibliothek wählen.");
    }
    SharePointSettings sharePointSettings = SharePointSettings.read(effective);
    RequestBudget budget = RequestBudget.unbounded();
    SharePointFileStore store =
        new SharePointFileStore(
            graphs.open(
                settings.withSourceUrl(apiBase.toString()), settings::sourceCredentials, budget),
            budget.meter(),
            sharePointSettings,
            graphs.properties().pageSize());
    List<Map<String, Object>> findings = new ArrayList<>();
    int reachable = 0;
    try {
      for (SharePointLibrary library : sharePointSettings.libraries()) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("driveId", library.driveId());
        try {
          store.requireReachable(library.container());
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
    int total = sharePointSettings.libraries().size();
    boolean all = reachable == total;
    int unreachable = total - reachable;
    String message;
    if (total == 1) {
      message =
          all
              ? "Anmeldung erfolgreich; die Dokumentbibliothek ist erreichbar."
              : "Die Dokumentbibliothek ist für die Anwendung nicht erreichbar.";
    } else {
      message =
          all
              ? "Anmeldung erfolgreich; alle " + total + " Dokumentbibliotheken sind erreichbar."
              : unreachable
                  + " von "
                  + total
                  + " Dokumentbibliotheken "
                  + (unreachable == 1 ? "ist" : "sind")
                  + " für die Anwendung nicht erreichbar.";
    }
    return new SourceConnectionTestResult(
        all, message, null, true, ConnectorData.of(Map.of("libraries", findings)));
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine SharePoint-Bibliothek";
  }

  /**
   * Lists in stages by the parameters of the query: sites matching {@code search} (needs {@code
   * Sites.Read.All}) or the site at {@code siteUrl}, the document libraries of {@code site}, the
   * folders of {@code drive} below {@code folder} or its root. Keys are {@code site:<id>}, {@code
   * drive:<id>} and {@code folder:<id>}.
   */
  @Override
  public SourceListing browse(Query query) {
    SourceSettings settings = query.settings();
    if (blankToNull(settings.sourceCredentials()) == null) {
      throw new ValidationException(
          "Für die Auflistung ist ein Zugang mit App-Registrierung erforderlich");
    }
    ConnectorData asked =
        settings.connectorSettings() == null
            ? ConnectorData.of(Map.of())
            : settings.connectorSettings();
    GraphClient graph =
        graphs.open(
            settings.withSourceUrl(apiBase.toString()),
            settings::sourceCredentials,
            RequestBudget.unbounded());
    try {
      if (asked.get("drive") != null) {
        String drive = SharePointLibrary.requireId(string(asked, "drive"));
        Object folder = asked.get("folder");
        String children =
            folder == null
                ? "drives/" + drive + "/root/children"
                : "drives/"
                    + drive
                    + "/items/"
                    + SharePointLibrary.requireId(string(asked, "folder"))
                    + "/children";
        return new SourceListing(
            true,
            collect(
                graph,
                children,
                Map.of("$select", "id,name,folder"),
                item -> item.get("folder") != null ? "folder:" + text(item, "id") : null,
                "name"),
            null);
      }
      if (asked.get("site") != null) {
        String site = requireSiteId(string(asked, "site"));
        return new SourceListing(
            true,
            collect(
                graph,
                "sites/" + site + "/drives",
                Map.of("$select", "id,name,driveType"),
                drive ->
                    "documentLibrary".equals(text(drive, "driveType"))
                        ? SharePointLibrary.KEY_PREFIX + text(drive, "id")
                        : null,
                "name"),
            null);
      }
      if (asked.get("siteUrl") != null) {
        JsonNode site = graph.get(sitePath(string(asked, "siteUrl")), Map.of());
        return new SourceListing(
            true,
            List.of(new SourceListing.Entry("site:" + text(site, "id"), siteName(site))),
            null);
      }
      if (asked.get("search") != null) {
        String search = string(asked, "search").strip();
        if (search.isEmpty() || search.length() > 200) {
          throw new ValidationException("Der Suchbegriff hat 1 bis 200 Zeichen.");
        }
        try {
          List<SourceListing.Entry> sites = new ArrayList<>();
          for (JsonNode site : graph.page("sites", Map.of("search", search), null).value()) {
            sites.add(new SourceListing.Entry("site:" + text(site, "id"), siteName(site)));
          }
          return new SourceListing(true, sites, null);
        } catch (GraphException e) {
          if (e.kind() == GraphException.Kind.FORBIDDEN) {
            return new SourceListing(
                false,
                List.of(),
                "Die Suche nach Sites braucht die Berechtigung Sites.Read.All. Bitte die Adresse"
                    + " der Site angeben.");
          }
          throw e;
        }
      }
      return new SourceListing(
          false, List.of(), "Bitte eine Site suchen oder ihre Adresse angeben.");
    } catch (GraphException e) {
      return new SourceListing(false, List.of(), browseFailure(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Auflistung wurde unterbrochen.");
    }
  }

  @FunctionalInterface
  private interface KeyOf {
    String keyOf(JsonNode item);
  }

  private static List<SourceListing.Entry> collect(
      GraphClient graph, String path, Map<String, String> query, KeyOf keyOf, String nameField)
      throws GraphException, InterruptedException {
    List<SourceListing.Entry> entries = new ArrayList<>();
    String token = null;
    for (int page = 0; page < MAX_BROWSE_PAGES; page++) {
      GraphPage answer = graph.page(path, query, token);
      for (JsonNode item : answer.value()) {
        String key = keyOf.keyOf(item);
        if (key != null && text(item, "id") != null) {
          entries.add(new SourceListing.Entry(key, text(item, nameField)));
        }
      }
      token = answer.nextToken();
      if (token == null) {
        break;
      }
    }
    return entries;
  }

  private static String browseFailure(GraphException e) {
    return switch (e.kind()) {
      case NOT_FOUND ->
          "Microsoft Graph kennt die Site oder Bibliothek nicht oder zeigt sie der Anwendung nicht.";
      case FORBIDDEN ->
          "Die Anwendung darf das nicht lesen. Bei der Berechtigung Sites.Selected muss die Site"
              + " für die App freigegeben sein.";
      default -> e.getMessage();
    };
  }

  /**
   * {@code sites/<host>:/<path>} for the address of a site, such as {@code
   * https://contoso.sharepoint.com/sites/team}; no request goes to that host itself.
   */
  static String sitePath(String siteUrl) {
    URI uri;
    try {
      uri = new URI(siteUrl.strip());
    } catch (URISyntaxException e) {
      throw invalidSiteUrl();
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || !HOST.matcher(uri.getHost()).matches()
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getRawUserInfo() != null
        || uri.getPort() != -1) {
      throw invalidSiteUrl();
    }
    String path = uri.getPath() == null ? "" : uri.getPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    if (path.contains("..") || path.contains(":")) {
      throw invalidSiteUrl();
    }
    return "sites/" + uri.getHost().toLowerCase() + ":" + (path.isEmpty() ? "/" : path);
  }

  private static ValidationException invalidSiteUrl() {
    return new ValidationException(
        "Die Adresse der Site ist eine https-Adresse ohne Port, Anmeldedaten, Abfrage und Anker,"
            + " etwa https://contoso.sharepoint.com/sites/team");
  }

  /**
   * A site id is {@code host,siteCollectionId,webId} with two GUIDs; a host of letters, digits,
   * dots and hyphens without an empty label, so nothing that could change a path.
   */
  static String requireSiteId(String id) {
    if (id == null || !SITE_ID.matcher(id).matches() || id.contains("..")) {
      throw new ValidationException("site ist die ID einer Site aus der Auflistung");
    }
    return id;
  }

  private static String siteName(JsonNode site) {
    String name = text(site, "displayName");
    return name != null ? name : text(site, "webUrl");
  }

  private static String string(ConnectorData data, String key) {
    if (!(data.get(key) instanceof String value)) {
      throw new ValidationException(key + " ist ein Text");
    }
    return value;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() || !value.isValueNode() ? null : value.asString();
  }

  // --- original --------------------------------------------------------------------------------

  /**
   * Downloads the file the document names, the way a run fetches it, from a library the settings
   * still name; the temp file is deleted when the served stream is closed. A file Graph does not
   * show is no original; Graph unreachable is {@link OriginalUnavailableException}.
   */
  @Override
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    String filePath = document.getFilePath();
    if (filePath == null
        || !filePath.startsWith(SharePointFileStore.PATH_PREFIX)
        || settings.sourceCredentials() == null) {
      return Optional.empty();
    }
    String[] parts = filePath.substring(SharePointFileStore.PATH_PREFIX.length()).split("/", -1);
    if (parts.length != 2
        || !SharePointLibrary.ID.matcher(parts[0]).matches()
        || !SharePointLibrary.ID.matcher(parts[1]).matches()) {
      return Optional.empty();
    }
    SharePointSettings configured;
    try {
      configured = SharePointSettings.stored(settings.connectorSettings());
    } catch (ValidationException e) {
      return Optional.empty();
    }
    if (configured == null || configured.library(parts[0]) == null) {
      return Optional.empty();
    }
    GraphClient graph =
        graphs.open(
            settings.withSourceUrl(apiBase.toString()),
            settings::sourceCredentials,
            RequestBudget.unbounded());
    Path file;
    try {
      file =
          graph.download(
              "drives/" + parts[0] + "/items/" + parts[1] + "/content",
              graphs.properties().maxFileSizeBytes());
    } catch (GraphException e) {
      return switch (e.kind()) {
        case NOT_FOUND, FORBIDDEN, TOO_LARGE, BLOCKED -> Optional.empty();
        default ->
            throw new OriginalUnavailableException(
                UNAVAILABLE,
                "SharePoint file of document " + document.getId() + " is not readable right now",
                e);
      };
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OriginalUnavailableException(
          UNAVAILABLE,
          "Reading the SharePoint file of document " + document.getId() + " was interrupted",
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
      log.warn("Downloaded SharePoint file of document {} could not be opened", document.getId());
      return Optional.empty();
    }
  }

  @Override
  public OptionalLong streamedOriginalBound() {
    return OptionalLong.of(graphs.properties().maxFileSizeBytes());
  }
}
