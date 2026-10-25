package io.opaa.indexing.source.sharepoint;

import static io.opaa.indexing.spi.ConnectorChecks.blankToNull;
import static io.opaa.indexing.spi.ConnectorChecks.fixedSourceUrl;
import static io.opaa.indexing.spi.ConnectorChecks.requireFixedAddress;
import static io.opaa.indexing.spi.ConnectorChecks.unreachable;
import static io.opaa.indexing.spi.ConnectorChecks.validateAtFixedAddress;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.compliance.DataFlow;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.spi.ClientAuthentication;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.OAuthAuth;
import io.opaa.indexing.spi.OriginalAccess;
import io.opaa.indexing.spi.OriginalUnavailableException;
import io.opaa.indexing.spi.ProfileDeclaration;
import io.opaa.indexing.spi.RequestBudget;
import io.opaa.indexing.spi.Revocation;
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
import io.opaa.msgraph.GraphClient;
import io.opaa.msgraph.GraphException;
import io.opaa.sourceaccess.ServerAddressRule;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The OneDrive for Business of a person as the source of her private library (ADR-0058). Only a
 * person's connected account signs in, by her OAuth consent at her tenant with delegated {@code
 * Files.Read}; the connector sees the access token alone. The address is fixed ({@code apiBase}).
 * The drive, its optional folders and the full-sync rhythm are the settings ({@link
 * OneDriveSettings}); every request naming the drive waits for {@link OwnDrive#require}, so a token
 * of another account never reads it.
 */
@DataFlow("source-onedrive")
public class OneDriveSourceConnector implements SourceConnector, SourceBrowser, OriginalAccess {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("ONEDRIVE");

  /** The authorization endpoint of Entra, completed with the profile's tenant. */
  public static final String AUTHORIZATION_TEMPLATE =
      "https://login.microsoftonline.com/"
          + Endpoint.WithTenant.PLACEHOLDER
          + "/oauth2/v2.0/authorize";

  /** The delegated permissions: reading the person's files, and a refresh token. */
  public static final String SCOPES = "Files.Read offline_access";

  static final String UNAVAILABLE =
      "OneDrive ist derzeit nicht erreichbar. Bitte später erneut versuchen.";

  private static final Logger log = LoggerFactory.getLogger(OneDriveSourceConnector.class);

  private static final String DRIVE_STATE = "oneDrive";

  static final String FOLDER_GONE = "Der Ordner ist im OneDrive nicht mehr vorhanden.";

  private final URI apiBase;
  private final SourceConnectorDescriptor descriptor;
  private final GraphConnections graphs;
  private final SourceSyncStateRepository syncStateRepository;

  OneDriveSourceConnector(
      URI apiBase,
      String authorizationTemplate,
      String tokenTemplate,
      GraphConnections graphs,
      SourceSyncStateRepository syncStateRepository) {
    this.apiBase = apiBase;
    this.graphs = graphs;
    this.syncStateRepository = syncStateRepository;
    this.descriptor =
        SourceConnectorDescriptor.remoteRun(TYPE, "OneDrive")
            .withDataFlow("source-onedrive")
            .withoutDeepLink()
            .withFullSyncInterval(graphs.properties().fullSyncInterval())
            .withProfiles(
                ProfileDeclaration.of(
                        ConnectionProfileSupport.REQUIRED,
                        SignIn.oauth(
                            new OAuthAuth(
                                new Endpoint.WithTenant(authorizationTemplate),
                                new Endpoint.WithTenant(tokenTemplate),
                                new Revocation.None(),
                                SCOPES,
                                Map.of("prompt", "select_account"),
                                ClientAuthentication.CLIENT_SECRET_POST),
                            ConnectionOwnership.PERSON))
                    .withAddress(ServerAddressRule.fixed(apiBase.toString())));
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public Set<String> settingsKeys() {
    return OneDriveSettings.KEYS;
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    return OneDriveSettings.read(requested).toData();
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
    OneDriveSettings settings = stored == null ? null : OneDriveSettings.stored(stored);
    Map<String, Object> state = new LinkedHashMap<>();
    state.put(DRIVE_STATE, settings == null ? null : settings.drive().coverage());
    return state;
  }

  /** A changed address or selection makes the next run a full one from scratch. */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged || changedSettings.contains(DRIVE_STATE)) {
      syncStateRepository.deleteByLibraryId(library.getId());
    }
  }

  /**
   * The owner Graph names for the token's OneDrive - e-mail address, else display name - without a
   * permission beyond {@code Files.Read}; empty where it names none.
   *
   * @throws SourceCredentialsException when Graph does not answer the lookup
   */
  @Override
  public Optional<String> connectedAccount(SourceSettings settings) {
    if (blankToNull(settings.sourceCredentials()) == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(OwnDrive.read(open(settings)).owner());
    } catch (GraphException e) {
      throw new SourceCredentialsException(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  // --- connection test and listing -------------------------------------------------------------

  /** Checks with the access token of the person's account that the chosen OneDrive is hers. */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    requireFixedAddress(TYPE, apiBase.toString(), settings);
    ConnectorData effective =
        settings.connectorSettings() != null ? settings.connectorSettings() : stored;
    if (blankToNull(settings.sourceCredentials()) == null) {
      return unreachable("Bitte zuerst das Microsoft-Konto verbinden.");
    }
    if (effective == null) {
      return unreachable("Bitte das OneDrive wählen.");
    }
    SharePointLibrary drive = OneDriveSettings.read(effective).drive();
    RequestBudget budget = RequestBudget.unbounded();
    GraphClient graph =
        graphs.open(
            settings.withSourceUrl(apiBase.toString()), settings::sourceCredentials, budget);
    try {
      OwnDrive.require(graph, drive.driveId());
      new DriveFileStore(
              DriveRule.ONEDRIVE,
              graph,
              budget.meter(),
              List.of(drive),
              graphs.properties().pageSize())
          .requireReachable(drive.container());
    } catch (FileAccessException.RunEnding e) {
      return new SourceConnectionTestResult(
          false, e.getMessage(), null, e.credentialsVerified(), null);
    } catch (FileAccessException e) {
      return new SourceConnectionTestResult(false, e.getMessage(), null, true, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Der Verbindungstest wurde unterbrochen.");
    }
    return new SourceConnectionTestResult(
        true, "Anmeldung erfolgreich; das OneDrive ist erreichbar.", null, true, null);
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine OneDrive-Bibliothek";
  }

  /**
   * Without parameters the one entry {@code drive:<id>}, the token's own OneDrive; with {@code
   * drive} and an optional {@code folder} the folders below as {@code folder:<id>}, only of that
   * own OneDrive. A refusal answers an incomplete, empty listing with the German reason.
   */
  @Override
  public SourceListing browse(Query query) {
    SourceSettings settings = query.settings();
    if (blankToNull(settings.sourceCredentials()) == null) {
      throw new ValidationException(
          "Für die Auflistung ist ein verbundenes Microsoft-Konto erforderlich");
    }
    ConnectorData asked =
        settings.connectorSettings() == null
            ? ConnectorData.of(Map.of())
            : settings.connectorSettings();
    String drive =
        asked.get("drive") == null
            ? null
            : SharePointLibrary.requireId(string(asked, "drive"), SharePointLibrary.Terms.DRIVE);
    String folder =
        drive == null || asked.get("folder") == null
            ? null
            : SharePointLibrary.requireId(string(asked, "folder"), SharePointLibrary.Terms.DRIVE);
    GraphClient graph = open(settings);
    try {
      OwnDrive own = OwnDrive.read(graph);
      String refusal = own.refusal(drive == null ? own.id() : drive);
      if (refusal != null) {
        return new SourceListing(false, List.of(), refusal);
      }
      if (drive == null) {
        return new SourceListing(
            true,
            List.of(
                new SourceListing.Entry(
                    SharePointLibrary.KEY_PREFIX + own.id(),
                    own.name() == null || own.name().isBlank() ? "OneDrive" : own.name())),
            null);
      }
    } catch (GraphException e) {
      return new SourceListing(false, List.of(), browseFailure(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Auflistung wurde unterbrochen.");
    }
    try {
      return SharePointSourceConnector.folders(graph, drive, folder);
    } catch (GraphException e) {
      return new SourceListing(
          false,
          List.of(),
          e.kind() == GraphException.Kind.NOT_FOUND && !e.consentMissing()
              ? FOLDER_GONE
              : browseFailure(e));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Auflistung wurde unterbrochen.");
    }
  }

  private static String browseFailure(GraphException e) {
    if (e.consentMissing()) {
      return e.getMessage();
    }
    return switch (e.kind()) {
      case NOT_FOUND -> OwnDrive.NO_DRIVE;
      case FORBIDDEN -> OwnDrive.NOT_READABLE;
      default -> e.getMessage();
    };
  }

  private static String string(ConnectorData data, String key) {
    if (!(data.get(key) instanceof String value)) {
      throw new ValidationException(key + " ist ein Text");
    }
    return value;
  }

  private GraphClient open(SourceSettings settings) {
    return graphs.open(
        settings.withSourceUrl(apiBase.toString()),
        settings::sourceCredentials,
        RequestBudget.unbounded());
  }

  // --- original --------------------------------------------------------------------------------

  /**
   * Downloads the file the document names, the way a run fetches it, from the OneDrive the settings
   * still name and the token's account owns; the temp file is deleted when the served stream is
   * closed. A file Graph does not show, or a OneDrive of another account, is no original.
   */
  @Override
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    Optional<DriveRule.ItemAddress> address = DriveRule.ONEDRIVE.address(document.getFilePath());
    if (address.isEmpty() || blankToNull(settings.sourceCredentials()) == null) {
      return Optional.empty();
    }
    String driveId = address.get().driveId();
    OneDriveSettings configured;
    try {
      configured = OneDriveSettings.stored(settings.connectorSettings());
    } catch (ValidationException e) {
      return Optional.empty();
    }
    if (configured == null || !configured.drive().driveId().equals(driveId)) {
      return Optional.empty();
    }
    GraphClient graph = open(settings);
    Path file;
    try {
      if (OwnDrive.read(graph).refusal(driveId) != null) {
        return Optional.empty();
      }
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
                "OneDrive file of document " + document.getId() + " is not readable right now",
                e);
      };
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OriginalUnavailableException(
          UNAVAILABLE,
          "Reading the OneDrive file of document " + document.getId() + " was interrupted",
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
      log.warn("Downloaded OneDrive file of document {} could not be opened", document.getId());
      return Optional.empty();
    }
  }

  @Override
  public OptionalLong streamedOriginalBound() {
    return OptionalLong.of(graphs.properties().maxFileSizeBytes());
  }
}
