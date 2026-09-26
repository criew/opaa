package io.opaa.indexing.source.filesystem;

import static io.opaa.indexing.source.ConnectorChecks.blankToNull;
import static io.opaa.indexing.source.ConnectorChecks.reachable;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.document.DocumentService;
import io.opaa.indexing.format.SupportedDocumentFormats;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A directory on the server (ADR-0018). The operator's {@link FilesystemPathAllowlist} is the
 * security boundary against path enumeration (#484, ADR-0018 Entscheidung 6): an empty allowlist
 * disables the type, and both saving and testing check a path against it before anything on disk is
 * touched. A test reports no more than a count - never a file name or listing.
 */
public class FilesystemSourceConnector implements SourceConnector {

  private static final Logger log = LoggerFactory.getLogger(FilesystemSourceConnector.class);

  private static final SourceConnectorDescriptor DESCRIPTOR =
      SourceConnectorDescriptor.runBased(DocumentSourceType.FILESYSTEM);

  private static final String DISABLED =
      "sourceType FILESYSTEM ist deaktiviert: der Betrieb hat keine Verzeichnisse für"
          + " Dateisystem-Bibliotheken freigegeben";
  private static final String OUTSIDE_ALLOWLIST =
      "sourcePath liegt außerhalb der vom Betrieb freigegebenen Verzeichnisse. Die"
          + " freigegebenen Basisverzeichnisse teilt die Systemverwaltung mit.";
  private static final String FORBIDDEN_FIELDS =
      "sourceUrl, sourceProxy und sourceCredentials sind für sourceType FILESYSTEM nicht"
          + " zulässig";
  private static final String NO_INSECURE_SSL =
      "sourceInsecureSsl ist für sourceType FILESYSTEM nicht zulässig";

  private final FilesystemPathAllowlist allowlist;
  private final DocumentService documentService;
  private final SupportedDocumentFormats supportedFormats;

  public FilesystemSourceConnector(
      FilesystemPathAllowlist allowlist,
      DocumentService documentService,
      SupportedDocumentFormats supportedFormats) {
    this.allowlist = allowlist;
    this.documentService = documentService;
    this.supportedFormats = supportedFormats;
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public SourceSettings validate(SourceSettings requested) {
    String sourcePath = requested.sourcePath();
    if (sourcePath == null) {
      throw new ValidationException("sourcePath ist erforderlich, wenn sourceType FILESYSTEM ist");
    }
    if (!sourcePath.startsWith("/")) {
      throw new ValidationException("sourcePath muss ein absoluter Pfad sein");
    }
    if (requested.sourceUrl() != null
        || requested.sourceProxy() != null
        || requested.sourceCredentials() != null) {
      throw new ValidationException(FORBIDDEN_FIELDS);
    }
    if (requested.sourceInsecureSsl()) {
      throw new ValidationException(NO_INSECURE_SSL);
    }
    requireAllowed(sourcePath);
    return requested;
  }

  private void requireAllowed(String sourcePath) {
    if (!allowlist.isConfigured()) {
      throw new ValidationException(DISABLED);
    }
    if (!allowlist.isAllowed(sourcePath)) {
      throw new ValidationException(OUTSIDE_ALLOWLIST);
    }
  }

  /**
   * Checks absoluteness with {@link Path#isAbsolute()} rather than {@link #validate}'s literal
   * {@code "/"}: this method touches the filesystem, and a portable check keeps it testable against
   * a real directory on every OS.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings) {
    String sourcePath = blankToNull(settings.sourcePath());
    if (sourcePath == null) {
      throw new ValidationException("sourcePath ist erforderlich, wenn sourceType FILESYSTEM ist");
    }
    if (blankToNull(settings.sourceUrl()) != null
        || blankToNull(settings.sourceProxy()) != null
        || blankToNull(settings.sourceCredentials()) != null) {
      throw new ValidationException(FORBIDDEN_FIELDS);
    }
    if (settings.sourceInsecureSsl()) {
      throw new ValidationException(NO_INSECURE_SSL);
    }
    if (!Path.of(sourcePath).isAbsolute()) {
      throw new ValidationException("sourcePath muss ein absoluter Pfad sein");
    }
    requireAllowed(sourcePath);

    Path directory = Path.of(sourcePath);
    if (!Files.exists(directory)) {
      return unreachable("Das Verzeichnis existiert nicht.");
    }
    if (!Files.isDirectory(directory)) {
      return unreachable("Der angegebene Pfad ist kein Verzeichnis.");
    }
    if (!Files.isReadable(directory)) {
      return unreachable("Das Verzeichnis ist für den Server nicht lesbar.");
    }
    try {
      DocumentService.DiscoveredFiles discovered =
          documentService.discoverFiles(directory, supportedFormats);
      long count = discovered.supported().size();
      return reachable(
          "Verzeichnis erreichbar, "
              + count
              + " "
              + (count == 1 ? "Dokument" : "Dokumente")
              + " gefunden.",
          count);
    } catch (IOException e) {
      log.warn("Filesystem source test failed to read {}: {}", sourcePath, e.getMessage());
      return unreachable("Das Verzeichnis konnte nicht gelesen werden.");
    }
  }
}
