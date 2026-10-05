package io.opaa.indexing.source.nextcloud;

import static io.opaa.indexing.source.ConnectorChecks.blankToNull;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.OriginalAccess;
import io.opaa.indexing.source.OriginalUnavailableException;
import io.opaa.indexing.source.ProfileDeclaration;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.ServedOriginals;
import io.opaa.indexing.source.SignIn;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.SourceTargetRefusedException;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.ServedContentTypes;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.knowledge.SourceType;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Nextcloud (ADR-0040, Nachtrag Nextcloud): {@code sourceUrl} is the instance address, stored
 * normalised; the credentials are an account's {@code Benutzername:App-Passwort} (Basic Auth) - a
 * technical user's for a library, a person's own for her private library - sent only to that
 * address; the folders are the connector settings. A changed address or changed folders discard the
 * sync state with its folder ETags, so the next run lists everything. Rights at Nextcloud are not
 * taken over; the library decides who reads.
 */
public class NextcloudSourceConnector implements SourceConnector, SourceBrowser, OriginalAccess {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("NEXTCLOUD");

  private static final Logger log = LoggerFactory.getLogger(NextcloudSourceConnector.class);

  private static final String FOLDERS_STATE = "nextcloudFolders";

  static final String UNAVAILABLE =
      "Die Nextcloud dieser Bibliothek ist derzeit nicht erreichbar. Bitte später erneut"
          + " versuchen.";

  private static final SourceConnectorDescriptor DESCRIPTOR =
      SourceConnectorDescriptor.remoteRun(TYPE, "Nextcloud")
          .withProfiles(
              ProfileDeclaration.of(
                  ConnectionProfileSupport.OPTIONAL,
                  SignIn.personalSecret(
                      PersonalSecretForm.USERNAME_AND_PASSWORD,
                      ConnectionOwnership.LIBRARY,
                      ConnectionOwnership.PERSON)));

  private final NextcloudProperties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final SourceRequestPolicy requestPolicy;
  private final SourceSyncStateRepository syncStateRepository;

  public NextcloudSourceConnector(
      NextcloudProperties properties,
      TargetAddressValidator targetAddressValidator,
      SourceRequestPolicy requestPolicy,
      SourceSyncStateRepository syncStateRepository) {
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.requestPolicy = requestPolicy;
    this.syncStateRepository = syncStateRepository;
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public Set<String> settingsKeys() {
    return NextcloudSourceSettings.KEYS;
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    return NextcloudSourceSettings.read(requested).toData();
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    if (requested.sourcePath() != null) {
      throw new ValidationException("sourcePath ist für sourceType NEXTCLOUD nicht zulässig");
    }
    NextcloudConnection connection = connectionOf(requested, requested.sourceCredentials());
    requireReachable(connection);
    NextcloudSourceSettings settings = NextcloudSourceSettings.read(requested.connectorSettings());
    return requested
        .withSourceUrl(connection.baseUrl().toString())
        .withConnectorSettings(settings.toData());
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
          NextcloudSourceSettings.read(requested.connectorSettings()).toData());
    }
    return requested;
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

  /** The folders are what every reader sees of the library's scope; they carry no credential. */
  @Override
  public ConnectorData settingsView(
      KnowledgeLibrary library, ConnectorData stored, boolean manager) {
    NextcloudSourceSettings settings = readableStored(library, stored);
    return settings == null ? null : settings.toData();
  }

  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library, ConnectorData stored) {
    NextcloudSourceSettings settings = readableStored(library, stored);
    return Map.of(FOLDERS_STATE, settings == null ? List.of() : settings.folders());
  }

  /** A new address or other folders void the remembered ETags and the resumption state. */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged || changedSettings.contains(FOLDERS_STATE)) {
      syncStateRepository.deleteByLibraryId(library.getId());
    }
  }

  /**
   * Signs in with the given account and lists each requested folder, or the stored library's, or
   * the account's root when neither names one; the count is the entries directly in them.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    if (blankToNull(settings.sourcePath()) != null) {
      throw new ValidationException("sourcePath ist für sourceType NEXTCLOUD nicht zulässig");
    }
    NextcloudConnection connection;
    try {
      connection = NextcloudConnection.of(settings, blankToNull(settings.sourceCredentials()));
    } catch (NextcloudConnection.InvalidNextcloudConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
    ConnectorData probed =
        settings.connectorSettings() != null ? settings.connectorSettings() : stored;
    List<String> folders =
        probed == null ? List.of("/") : NextcloudSourceSettings.read(probed).folders();
    try (NextcloudDav dav = probe(connection)) {
      String filesRoot = dav.filesRoot();
      long entries = 0;
      List<String> missing = new ArrayList<>();
      for (String folder : folders) {
        try {
          entries +=
              Math.max(
                  0,
                  dav.propfind(folderPath(filesRoot, folder), 1, "den Ordner „" + folder + "“")
                          .size()
                      - 1);
        } catch (NextcloudAccessException.NotFound | NextcloudAccessException.Forbidden e) {
          missing.add(folder);
        }
      }
      if (!missing.isEmpty()) {
        return new SourceConnectionTestResult(
            false,
            "Angemeldet, aber diese Ordner sind für dieses Konto nicht lesbar: "
                + String.join(", ", missing),
            null,
            true,
            null);
      }
      return new SourceConnectionTestResult(
          true,
          "Verbindung hergestellt; " + folders.size() + " Ordner lesbar.",
          entries,
          true,
          null);
    } catch (NextcloudAccessException.Authentication e) {
      return new SourceConnectionTestResult(false, e.getMessage(), null, false, null);
    } catch (NextcloudAccessException e) {
      return unreachable(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Der Verbindungstest wurde unterbrochen.");
    }
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine Nextcloud-Bibliothek";
  }

  /**
   * The folders directly in the signed-in account's root - own ones, shares and group folders - as
   * candidates for the folder selection.
   */
  @Override
  public SourceListing browse(Query query) {
    SourceSettings settings = query.settings();
    String credentials = blankToNull(settings.sourceCredentials());
    if (credentials == null) {
      throw new ValidationException("sourceCredentials sind für die Ordnerauswahl erforderlich");
    }
    NextcloudConnection connection;
    try {
      connection = NextcloudConnection.of(settings, credentials);
    } catch (NextcloudConnection.InvalidNextcloudConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
    try (NextcloudDav dav = probe(connection)) {
      String filesRoot = dav.filesRoot();
      List<SourceListing.Entry> entries = new ArrayList<>();
      for (DavResource resource : dav.propfind(filesRoot + "/", 1, "den Stammordner")) {
        if (resource.collection()
            && !resource.name().isEmpty()
            && !resource.path().equals(DavPaths.decode(filesRoot))) {
          entries.add(new SourceListing.Entry("/" + resource.name(), label(resource)));
        }
      }
      return new SourceListing(true, entries, null);
    } catch (NextcloudAccessException e) {
      throw new ValidationException(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Ordnerauswahl wurde unterbrochen.");
    }
  }

  /**
   * The file, found again at the place its row records and confirmed by its file id, downloaded
   * like a run reads it; the temp file is deleted when the served stream closes. A file moved since
   * the last run, or outside the configured folders, has no original until the next run.
   */
  @Override
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    NextcloudConnection connection;
    NextcloudSourceSettings folders;
    try {
      connection = NextcloudConnection.of(settings, settings.sourceCredentials());
      folders = NextcloudSourceSettings.read(settings.connectorSettings());
    } catch (ValidationException | NextcloudConnection.InvalidNextcloudConfigurationException e) {
      log.warn(
          "Library {} has an unusable Nextcloud configuration: {}",
          library.getId(),
          e.getMessage());
      return Optional.empty();
    }
    String prefix = connection.baseUrl() + "/index.php/f/";
    String folder = document.getSourceContainerKey();
    if (!document.getFilePath().startsWith(prefix) || !folders.folders().contains(folder)) {
      return Optional.empty();
    }
    String fileId = document.getFilePath().substring(prefix.length());
    try (NextcloudDav dav = probe(connection)) {
      String path = originalPath(dav.filesRoot(), folder, document);
      List<DavResource> found = dav.propfind(path, 0, "die Datei „" + document.getFileName() + "“");
      if (found.isEmpty() || !fileId.equals(found.getFirst().fileId())) {
        log.info("Document {} is no longer at its recorded place", document.getId());
        return Optional.empty();
      }
      BoundedDownloader.DownloadedFile file =
          dav.download(path, document.getFileName(), properties.maxFileSizeBytes());
      try {
        String contentType = document.getContentType();
        if (contentType == null || contentType.isBlank()) {
          contentType = ServedOriginals.normalizeContentType(file.contentType());
        }
        InputStream stream =
            ServedOriginals.deletingOnClose(
                Files.newInputStream(file.path()), List.of(file.path()));
        return Optional.of(
            DocumentContent.ofStream(
                stream,
                document.getFileName(),
                ServedContentTypes.forFile(contentType, file.path())));
      } catch (IOException e) {
        ServedOriginals.deleteQuietly(file.path());
        return Optional.empty();
      }
    } catch (NextcloudAccessException.NotFound
        | NextcloudAccessException.Forbidden
        | NextcloudAccessException.TooLarge e) {
      return Optional.empty();
    } catch (NextcloudAccessException e) {
      throw new OriginalUnavailableException(
          UNAVAILABLE, "Nextcloud original of document " + document.getId() + " unreachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OriginalUnavailableException(
          UNAVAILABLE, "Reading the original of document " + document.getId() + " interrupted", e);
    }
  }

  @Override
  public OptionalLong streamedOriginalBound() {
    return OptionalLong.of(properties.maxFileSizeBytes());
  }

  /** The configured folder, the recorded hierarchy path and the file name as a WebDAV path. */
  static String originalPath(String filesRoot, String folder, Document document) {
    List<String> segments = new ArrayList<>(NextcloudSourceSettings.segments(folder));
    String hierarchy = document.getSourceHierarchyPath();
    if (hierarchy != null && !hierarchy.isEmpty()) {
      segments.addAll(List.of(hierarchy.split(SourceDocumentContext.HIERARCHY_SEPARATOR)));
    }
    segments.add(document.getFileName());
    return filesRoot + "/" + DavPaths.encodePath(String.join("/", segments));
  }

  static String folderPath(String filesRoot, String folder) {
    return folder.equals("/") ? filesRoot + "/" : filesRoot + DavPaths.encodePath(folder) + "/";
  }

  private NextcloudDav probe(NextcloudConnection connection) {
    return new NextcloudDav(
        connection,
        targetAddressValidator,
        requestPolicy,
        RequestBudget.unbounded(),
        PROBE_TIMEOUT,
        properties.maxResponseBytes());
  }

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

  private static String label(DavResource resource) {
    if ("shared".equals(resource.mountType())) {
      return resource.name() + " (Freigabe)";
    }
    if ("group".equals(resource.mountType())) {
      return resource.name() + " (Gruppenordner)";
    }
    return resource.name();
  }

  private NextcloudConnection connectionOf(SourceSettings settings, String credentials) {
    if (credentials == null) {
      throw new ValidationException(
          "sourceCredentials (Benutzername:App-Passwort) sind erforderlich, wenn sourceType"
              + " NEXTCLOUD ist");
    }
    try {
      return NextcloudConnection.of(settings, credentials);
    } catch (NextcloudConnection.InvalidNextcloudConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
  }

  /**
   * The address and the proxy pass the target validation before anything is stored; a refused
   * target is a refusal of the connection, not of a setting.
   */
  private void requireReachable(NextcloudConnection connection) {
    try {
      targetAddressValidator.validate(connection.baseUrl());
      if (connection.proxyHost() != null) {
        targetAddressValidator.validateHost(connection.proxyHost());
      }
    } catch (IOException e) {
      throw new SourceTargetRefusedException(e.getMessage() + " " + NextcloudDav.ALLOWLIST_HINT);
    }
  }

  private static NextcloudSourceSettings readableStored(
      KnowledgeLibrary library, ConnectorData stored) {
    try {
      return NextcloudSourceSettings.stored(stored);
    } catch (ValidationException e) {
      log.warn(
          "Library {} carries Nextcloud settings the record rejects; left out: {}",
          library.getId(),
          e.getMessage());
      return null;
    }
  }
}
