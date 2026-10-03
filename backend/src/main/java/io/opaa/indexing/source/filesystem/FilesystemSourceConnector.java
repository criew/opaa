package io.opaa.indexing.source.filesystem;

import static io.opaa.indexing.source.ConnectorChecks.blankToNull;
import static io.opaa.indexing.source.ConnectorChecks.unreachable;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.FilesystemPathAllowlist;
import io.opaa.indexing.source.OriginalAccess;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorDescriptor;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.ServedContentTypes;
import io.opaa.knowledge.SourceType;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A directory on the server (ADR-0018). The operator's {@link FilesystemPathAllowlist} is the
 * security boundary against path enumeration (#484, ADR-0018 Entscheidung 6): an empty allowlist
 * disables the type, and both saving and testing check a path against it before anything on disk is
 * touched. A test only opens the directory itself - it reads nothing below it and reports neither a
 * count nor a file name.
 *
 * <p>An original is served only when its path resolves - symlinks included - underneath the
 * library's own {@code sourcePath}, and only while that path is still inside the allowlist: an
 * allowlist narrowed or emptied after indexing must not leave the files readable here.
 *
 * <p>The connector settings are {@link FilesystemSourceSettings}, applied by the run.
 */
public class FilesystemSourceConnector implements SourceConnector, OriginalAccess {

  /** The type key this connector serves. */
  public static final SourceType TYPE = SourceType.of("FILESYSTEM");

  private static final Logger log = LoggerFactory.getLogger(FilesystemSourceConnector.class);

  private static final SourceConnectorDescriptor DESCRIPTOR =
      SourceConnectorDescriptor.localRun(TYPE, "Dateisystem");

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

  public FilesystemSourceConnector(FilesystemPathAllowlist allowlist) {
    this.allowlist = allowlist;
  }

  @Override
  public SourceConnectorDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public Optional<DocumentContent> openOriginal(
      Document document, KnowledgeLibrary library, SourceSettings settings) {
    Path file = fileWithinConfiguredDirectory(document, library);
    if (file == null || !Files.isRegularFile(file)) {
      return Optional.empty();
    }
    return Optional.of(
        new DocumentContent(
            file,
            document.getFileName(),
            ServedContentTypes.forFile(document.getContentType(), file)));
  }

  /**
   * {@code document}'s file, resolved with {@link Path#toRealPath}, when it lies underneath the
   * library's resolved {@code sourcePath}; {@code null} otherwise, also for a file that is gone.
   */
  private Path fileWithinConfiguredDirectory(Document document, KnowledgeLibrary library) {
    if (document.getFilePath() == null || library.getSourcePath() == null) {
      return null;
    }
    if (!allowlist.isAllowed(library.getSourcePath())) {
      return null;
    }
    Path candidate = resolveReal(Path.of(document.getFilePath()));
    Path configuredDirectory = resolveReal(Path.of(library.getSourcePath()));
    if (candidate == null || configuredDirectory == null) {
      return null;
    }
    return candidate.startsWith(configuredDirectory) ? candidate : null;
  }

  private static Path resolveReal(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return null;
    }
  }

  @Override
  public ConnectorData readSettings(ConnectorData requested) {
    return FilesystemSourceSettings.of(requested).toData();
  }

  @Override
  public void configureNew(KnowledgeLibrary library, SourceSettings validated) {
    if (validated.connectorSettings() != null) {
      library.updateSourceSettings(validated.connectorSettings().toJson());
    }
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
    return Map.of(
        FilesystemSourceSettings.EXCLUDE_PATTERNS,
        FilesystemSourceSettings.of(stored).excludePatterns());
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
   * a real directory on every OS. Opening the directory is the whole test: no walk, no file read,
   * no count - the indexing run reports the documents.
   */
  @Override
  public SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored) {
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
    try (DirectoryStream<Path> ignored = Files.newDirectoryStream(directory)) {
      return new SourceConnectionTestResult(true, "Verzeichnis erreichbar.", null);
    } catch (IOException e) {
      log.warn("Filesystem source test failed to read {}: {}", sourcePath, e.getMessage());
      return unreachable("Das Verzeichnis konnte nicht gelesen werden.");
    }
  }
}
