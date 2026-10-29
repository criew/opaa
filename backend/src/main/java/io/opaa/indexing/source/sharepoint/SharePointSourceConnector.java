package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.spi.ConnectorChecks.blankToNull;
import static io.opaa.indexing.spi.ConnectorChecks.fixedSourceUrl;
import static io.opaa.indexing.spi.ConnectorChecks.requireFixedAddress;
import static io.opaa.indexing.spi.ConnectorChecks.unreachable;
import static io.opaa.indexing.spi.ConnectorChecks.validateAtFixedAddress;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.compliance.DataFlow;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.spi.ClientAuthentication;
import io.opaa.indexing.spi.ClientCredentialsAuth;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.OriginalAccess;
import io.opaa.indexing.spi.OriginalUnavailableException;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.ServedOriginals;
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
import io.opaa.msgraph.GraphClient;
import io.opaa.msgraph.GraphException;
import io.opaa.msgraph.GraphPage;
import io.opaa.sourceaccess.ServerAddressRule;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * SharePoint document libraries as the source of a library (ADR-0047). The address is fixed ({@code
 * apiBase}); only a profile signs in, with the client credentials of an app registration in Entra,
 * and the connector sees the access token alone. The libraries, their optional folders and the
 * full-sync rhythm are the connector settings ({@link SharePointSettings}); a changed address or
 * selection discards the run state. Documents carry no deep link - the original comes through
 * Graph.
 */
@DataFlow("source-sharepoint")
public class SharePointSourceConnector implements SourceConnector, SourceBrowser, OriginalAccess {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("SHAREPOINT");

  /**
   * The factory default of the full-sync rhythm: daily, because only the full sync follows renamed,
   * moved or deleted folders. The administration may set another for the installation, a library
   * its own ({@code FullSyncRhythm}).
   */
  public static final Duration FULL_SYNC_INTERVAL = Duration.ofDays(1);

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
            .withDataFlow("source-sharepoint")
            .withoutDeepLink()
            .withFullSyncInterval(FULL_SYNC_INTERVAL)
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
    return fixedSourceUrl(apiBase.toString(), requested);
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    return validateAtFixedAddress(TYPE, apiBase.toString(), requested, this::readSettings);
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
   * Checks each requested library (or the stored ones) with the access token the core obtained, a
   * library stored with its site also against the site's document libraries; the finding per
   * library is the {@code libraries} detail.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    requireFixedAddress(TYPE, apiBase.toString(), settings);
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
    GraphClient graph =
        graphs.open(
            settings.withSourceUrl(apiBase.toString()), settings::sourceCredentials, budget);
    DriveFileStore store =
        new DriveFileStore(
            DriveRule.SHAREPOINT,
            graph,
            budget.meter(),
            sharePointSettings.libraries(),
            graphs.properties().pageSize());
    List<Map<String, Object>> findings = new ArrayList<>();
    Map<String, SiteDrives> siteDrives = new HashMap<>();
    int reachable = 0;
    try {
      for (SharePointLibrary library : sharePointSettings.libraries()) {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("driveId", library.driveId());
        try {
          store.requireReachable(library.container());
          String outside = outsideSite(graph, library, siteDrives);
          finding.put("reachable", outside == null);
          if (outside == null) {
            reachable++;
          } else {
            finding.put("message", outside);
          }
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

  /**
   * Why {@code library} does not lie in the site it is stored with, {@code null} when it does or
   * names none. The document libraries of each site are listed once per test, in {@code listed}; a
   * listing cut at its page limit proves no absence.
   */
  private static String outsideSite(
      GraphClient graph, SharePointLibrary library, Map<String, SiteDrives> listed)
      throws InterruptedException {
    SharePointSite site = library.site();
    if (site == null) {
      return null;
    }
    SiteDrives drives = listed.get(site.id());
    if (drives == null) {
      try {
        SourceListing listing = documentLibraries(graph, site.id());
        drives =
            new SiteDrives(
                listing.entries().stream().map(SourceListing.Entry::key).toList(),
                listing.complete(),
                null);
      } catch (GraphException e) {
        drives = new SiteDrives(List.of(), false, browseFailure(e));
      }
      listed.put(site.id(), drives);
    }
    if (drives.failure() != null) {
      return drives.failure();
    }
    if (drives.keys().contains(library.key()) || !drives.complete()) {
      return null;
    }
    return "Die Dokumentbibliothek gehört nicht zur "
        + site.shown()
        + "; bitte neu aus der Auflistung wählen.";
  }

  /** The document library keys of a site, whether that is all of them, or why Graph refused. */
  private record SiteDrives(List<String> keys, boolean complete, String failure) {}

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
        return folders(
            graph,
            drive,
            folder == null ? null : SharePointLibrary.requireId(string(asked, "folder")));
      }
      if (asked.get("site") != null) {
        return documentLibraries(graph, requireSiteId(string(asked, "site")));
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
          return collect(
              graph,
              "sites",
              Map.of("search", search),
              site -> new SourceListing.Entry("site:" + text(site, "id"), siteName(site)),
              "Sites",
              "Bitte den Suchbegriff eingrenzen oder die Adresse der Site angeben.");
        } catch (GraphException e) {
          if (e.kind() == GraphException.Kind.FORBIDDEN && !e.consentMissing()) {
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

  /**
   * The folders directly below {@code folder} of {@code drive}, or below its root for {@code null},
   * keyed {@code folder:<id>}; both ids are checked by the caller. A shortcut to a folder of
   * another drive ({@code remoteItem}) is not offered.
   */
  static SourceListing folders(GraphClient graph, String drive, String folder)
      throws GraphException, InterruptedException {
    String children =
        folder == null
            ? "drives/" + drive + "/root/children"
            : "drives/" + drive + "/items/" + folder + "/children";
    return collect(
        graph,
        children,
        Map.of("$select", "id,name,folder,remoteItem"),
        item ->
            item.get("folder") != null && item.get("remoteItem") == null
                ? new SourceListing.Entry("folder:" + text(item, "id"), text(item, "name"))
                : null,
        "Ordner",
        "Weitere zeigt die Auswahl nicht.");
  }

  /** The document libraries of {@code site}, a checked site id, keyed {@code drive:<id>}. */
  private static SourceListing documentLibraries(GraphClient graph, String site)
      throws GraphException, InterruptedException {
    return collect(
        graph,
        "sites/" + site + "/drives",
        Map.of("$select", "id,name,driveType"),
        drive ->
            "documentLibrary".equals(text(drive, "driveType"))
                ? new SourceListing.Entry(
                    SharePointLibrary.KEY_PREFIX + text(drive, "id"), text(drive, "name"))
                : null,
        "Dokumentbibliotheken",
        "Weitere zeigt die Auswahl nicht.");
  }

  @FunctionalInterface
  private interface EntryOf {
    /** The entry {@code item} offers, {@code null} for none. */
    SourceListing.Entry entryOf(JsonNode item);
  }

  /**
   * The entries of up to {@link #MAX_BROWSE_PAGES} pages of {@code path}; a further page left
   * unread makes the listing incomplete, named by {@code noun} and {@code hint}.
   */
  private static SourceListing collect(
      GraphClient graph,
      String path,
      Map<String, String> query,
      EntryOf entryOf,
      String noun,
      String hint)
      throws GraphException, InterruptedException {
    List<SourceListing.Entry> entries = new ArrayList<>();
    String token = null;
    for (int page = 0; page < MAX_BROWSE_PAGES; page++) {
      GraphPage answer = graph.page(path, query, token);
      for (JsonNode item : answer.value()) {
        SourceListing.Entry entry = text(item, "id") == null ? null : entryOf.entryOf(item);
        if (entry != null) {
          entries.add(entry);
        }
      }
      token = answer.nextToken();
      if (token == null) {
        return new SourceListing(true, entries, null);
      }
    }
    return SourceListing.cutAt(entries, noun, hint);
  }

  private static String browseFailure(GraphException e) {
    if (e.consentMissing()) {
      return e.getMessage();
    }
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

  /** A site id as {@link SharePointSite#isId} accepts it, so nothing that could change a path. */
  static String requireSiteId(String id) {
    if (!SharePointSite.isId(id)) {
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
    Optional<DriveRule.ItemAddress> address = DriveRule.SHAREPOINT.address(document.getFilePath());
    if (address.isEmpty() || settings.sourceCredentials() == null) {
      return Optional.empty();
    }
    String driveId = address.get().driveId();
    SharePointSettings configured;
    try {
      configured = SharePointSettings.stored(settings.connectorSettings());
    } catch (ValidationException e) {
      return Optional.empty();
    }
    if (configured == null || configured.library(driveId) == null) {
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
              "drives/" + driveId + "/items/" + address.get().itemId() + "/content",
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
