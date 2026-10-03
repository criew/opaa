package io.opaa.indexing.source.smb;

import static io.opaa.indexing.source.ConnectorChecks.blankToNull;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.OriginalAccess;
import io.opaa.indexing.source.OriginalUnavailableException;
import io.opaa.indexing.source.RequestBudget;
import io.opaa.indexing.source.ServedOriginals;
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
import io.opaa.security.TargetAddressValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * A Windows file share (ADR-0040, Nachtrag SMB): {@code sourceUrl} is {@code
 * smb://server/freigabe}, stored normalised; the credentials are the service account's {@code
 * DOMÄNE\Benutzer:Passwort}, used with NTLM and only against that server; the folders are the
 * connector settings. A document has no deep link, its original is read from the share. Rights on
 * the share are not taken over; the library decides who reads.
 */
public class SmbSourceConnector implements SourceConnector, SourceBrowser, OriginalAccess {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("SMB");

  private static final Logger log = LoggerFactory.getLogger(SmbSourceConnector.class);

  private static final String FOLDERS_STATE = "smbFolders";
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

  static final String UNAVAILABLE =
      "Die Dateifreigabe dieser Bibliothek ist derzeit nicht erreichbar. Bitte später erneut"
          + " versuchen.";

  private static final SourceConnectorDescriptor DESCRIPTOR =
      SourceConnectorDescriptor.remoteRun(TYPE, "Windows-Dateifreigabe (SMB)").withoutDeepLink();

  private final SmbProperties properties;
  private final TargetAddressValidator targetAddressValidator;
  private final SourceSyncStateRepository syncStateRepository;

  public SmbSourceConnector(
      SmbProperties properties,
      TargetAddressValidator targetAddressValidator,
      SourceSyncStateRepository syncStateRepository) {
    this.properties = properties;
    this.targetAddressValidator = targetAddressValidator;
    this.syncStateRepository = syncStateRepository;
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    return SmbSourceSettings.read(requested).toData();
  }

  @Override
  public String normalizeSourceUrl(String requested) {
    try {
      return SmbAddress.parse(requested).url();
    } catch (SmbAddress.InvalidSmbConfigurationException e) {
      return requested;
    }
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    requireNoHttpOptions(requested);
    SmbAddress address = address(requested);
    if (blankToNull(requested.sourceCredentials()) == null) {
      throw new ValidationException(
          "sourceCredentials (DOMÄNE\\Benutzer:Passwort des Dienstkontos) sind erforderlich, wenn"
              + " sourceType SMB ist");
    }
    credentials(requested.sourceCredentials());
    requireReachable(address);
    SmbSourceSettings settings = SmbSourceSettings.read(requested.connectorSettings());
    return requested.withSourceUrl(address.url()).withConnectorSettings(settings.toData());
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
          SmbSourceSettings.read(requested.connectorSettings()).toData());
    }
    return requested;
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    library.updateSourceSettings(
        SmbSourceSettings.read(validated.connectorSettings()).toData().toJson());
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
    SmbSourceSettings settings = readableStored(library, stored);
    return settings == null ? null : settings.toData();
  }

  @Override
  public Map<String, Object> settingsState(KnowledgeLibrary library, ConnectorData stored) {
    SmbSourceSettings settings = readableStored(library, stored);
    return Map.of(FOLDERS_STATE, settings == null ? List.of() : settings.folders());
  }

  /** A new address or other folders void the resumption state. */
  @Override
  public void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {
    if (addressChanged || changedSettings.contains(FOLDERS_STATE)) {
      syncStateRepository.deleteByLibraryId(library.getId());
    }
  }

  /**
   * Signs in as the service account, opens the share and lists each requested folder, or the stored
   * library's, or the share's root when neither names one; the count is the entries directly in
   * them. Each finding - sign-in, share, folder, read right - is a sentence of its own.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
    requireNoHttpOptions(settings);
    SmbAddress address = address(settings);
    String secret = blankToNull(settings.sourceCredentials());
    if (secret == null) {
      throw new ValidationException("sourceCredentials sind für den Verbindungstest erforderlich");
    }
    SmbCredentials credentials = credentials(secret);
    ConnectorData probed =
        settings.connectorSettings() != null ? settings.connectorSettings() : stored;
    List<String> folders = SmbSourceSettings.read(probed).folders();
    try (SmbShareClient smb = probe(address, credentials)) {
      try {
        smb.connect();
      } catch (SmbAccessException.Authentication e) {
        return new SourceConnectionTestResult(false, e.getMessage(), null, false, null);
      } catch (SmbAccessException.ShareNotFound | SmbAccessException.AccessDenied e) {
        return new SourceConnectionTestResult(
            false, "Angemeldet, aber: " + e.getMessage(), null, true, null);
      }
      long entries = 0;
      List<String> missing = new ArrayList<>();
      List<String> denied = new ArrayList<>();
      for (String folder : folders) {
        try (SmbShareClient.Listing listing = smb.list(SmbSourceSettings.sharePath(folder))) {
          while (listing.hasNext()) {
            listing.next();
            entries++;
          }
        } catch (SmbAccessException.NotFound e) {
          missing.add(folder);
        } catch (SmbAccessException.AccessDenied e) {
          denied.add(folder);
        } catch (SmbShareClient.ListingFailure e) {
          denied.add(folder);
        }
      }
      if (!missing.isEmpty() || !denied.isEmpty()) {
        List<String> findings = new ArrayList<>();
        if (!missing.isEmpty()) {
          findings.add("diese Ordner gibt es nicht: " + String.join(", ", missing));
        }
        if (!denied.isEmpty()) {
          findings.add(
              "diese Ordner darf das Dienstkonto nicht lesen: " + String.join(", ", denied));
        }
        return new SourceConnectionTestResult(
            false, "Angemeldet, aber " + String.join("; ", findings) + ".", null, true, null);
      }
      return new SourceConnectionTestResult(
          true,
          "Verbindung hergestellt; Freigabe „"
              + address.share()
              + "“ und "
              + folders.size()
              + " Ordner lesbar.",
          entries,
          true,
          null);
    } catch (SmbAccessException e) {
      return unreachable(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return unreachable("Der Verbindungstest wurde unterbrochen.");
    }
  }

  @Override
  public String otherTypeMessage() {
    return "Die Bibliothek ist keine SMB-Bibliothek";
  }

  /** The folders directly in the share's root, as candidates for the folder selection. */
  @Override
  public SourceListing browse(Query query) {
    SourceSettings settings = query.settings();
    requireNoHttpOptions(settings);
    SmbAddress address = address(settings);
    String secret = blankToNull(settings.sourceCredentials());
    if (secret == null) {
      throw new ValidationException("sourceCredentials sind für die Ordnerauswahl erforderlich");
    }
    try (SmbShareClient smb = probe(address, credentials(secret));
        SmbShareClient.Listing listing = smb.list("")) {
      List<SourceListing.Entry> entries = new ArrayList<>();
      while (listing.hasNext()) {
        SmbShareClient.Item item = listing.next();
        if (item.directory() && !item.link()) {
          entries.add(new SourceListing.Entry("/" + item.name(), item.name()));
        }
      }
      return new SourceListing(true, entries, null);
    } catch (SmbAccessException.AccessDenied e) {
      return new SourceListing(
          false,
          List.of(),
          "Das Dienstkonto darf den Stammordner der Freigabe nicht auflisten. Die Ordnerpfade"
              + " lassen sich von Hand eintragen.");
    } catch (SmbAccessException e) {
      throw new ValidationException(e.getMessage());
    } catch (SmbShareClient.ListingFailure e) {
      throw new ValidationException(e.failure().getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ValidationException("Die Ordnerauswahl wurde unterbrochen.");
    }
  }

  /**
   * The file at the place its row records, read like a run reads it; the temp file is deleted when
   * the served stream closes. A file outside the configured folders has no original.
   */
  @Override
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    SmbAddress address;
    SmbCredentials credentials;
    SmbSourceSettings folders;
    try {
      address = SmbAddress.parse(settings.sourceUrl());
      credentials = SmbCredentials.parse(settings.sourceCredentials());
      folders = SmbSourceSettings.read(settings.connectorSettings());
    } catch (ValidationException | SmbAddress.InvalidSmbConfigurationException e) {
      log.warn("Library {} has an unusable SMB configuration: {}", library.getId(), e.getMessage());
      return Optional.empty();
    }
    Optional<String> relative = address.relativePath(document.getFilePath());
    if (relative.isEmpty() || !inFolders(relative.get(), folders)) {
      return Optional.empty();
    }
    try (SmbShareClient smb = probe(address, credentials)) {
      Path file =
          smb.download(relative.get(), document.getFileName(), properties.maxFileSizeBytes());
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
        return Optional.empty();
      }
    } catch (SmbAccessException.NotFound
        | SmbAccessException.AccessDenied
        | SmbAccessException.TooLarge e) {
      return Optional.empty();
    } catch (SmbAccessException e) {
      throw new OriginalUnavailableException(
          UNAVAILABLE, "SMB original of document " + document.getId() + " unreachable", e);
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

  /** Whether {@code relative} lies in one of the configured folders and names no {@code ..}. */
  static boolean inFolders(String relative, SmbSourceSettings settings) {
    for (String segment : relative.split("/")) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return false;
      }
    }
    for (String folder : settings.folders()) {
      String path = SmbSourceSettings.sharePath(folder);
      if (path.isEmpty() || relative.startsWith(path + "/")) {
        return true;
      }
    }
    return false;
  }

  private SmbShareClient probe(SmbAddress address, SmbCredentials credentials) {
    return SmbShareClient.of(
        address, credentials, targetAddressValidator, RequestBudget.unbounded(), PROBE_TIMEOUT);
  }

  /** The HTTP-only fields have no meaning for a share and are refused rather than ignored. */
  private static void requireNoHttpOptions(SourceSettings settings) {
    if (blankToNull(settings.sourcePath()) != null) {
      throw new ValidationException("sourcePath ist für sourceType SMB nicht zulässig");
    }
    if (blankToNull(settings.sourceProxy()) != null) {
      throw new ValidationException("sourceProxy ist für sourceType SMB nicht zulässig");
    }
    if (settings.sourceInsecureSsl()) {
      throw new ValidationException("sourceInsecureSsl ist für sourceType SMB nicht zulässig");
    }
  }

  private static SmbAddress address(SourceSettings settings) {
    try {
      return SmbAddress.parse(settings.sourceUrl());
    } catch (SmbAddress.InvalidSmbConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
  }

  private static SmbCredentials credentials(String secret) {
    try {
      return SmbCredentials.parse(secret);
    } catch (SmbAddress.InvalidSmbConfigurationException e) {
      throw new ValidationException(e.getMessage());
    }
  }

  /** The server passes the target validation before anything is stored. */
  private void requireReachable(SmbAddress address) {
    try {
      targetAddressValidator.validateHost(address.socketHost());
    } catch (TargetAddressValidator.UnknownTargetHostException e) {
      throw new ValidationException(e.getMessage());
    } catch (IOException e) {
      throw new ValidationException(e.getMessage() + " " + SmbShareClient.ALLOWLIST_HINT);
    }
  }

  private static SmbSourceSettings readableStored(KnowledgeLibrary library, ConnectorData stored) {
    try {
      return stored == null ? null : SmbSourceSettings.read(stored);
    } catch (ValidationException e) {
      log.warn(
          "Library {} carries SMB settings the record rejects; left out: {}",
          library.getId(),
          e.getMessage());
      return null;
    }
  }
}
