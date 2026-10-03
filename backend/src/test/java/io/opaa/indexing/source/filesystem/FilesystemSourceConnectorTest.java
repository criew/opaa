package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.ValidationException;
import io.opaa.format.DocumentService;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.FilesystemPathAllowlist;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.test.ProductionDocumentFormats;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSourceConnectorTest {

  @TempDir Path dir;

  @Test
  void aReadFailureEscapingTheWalkUncheckedIsReportedAsUnreadableNotThrown() throws IOException {
    FilesystemPathAllowlist allowlist = mock(FilesystemPathAllowlist.class);
    when(allowlist.isConfigured()).thenReturn(true);
    when(allowlist.isAllowed(dir.toString())).thenReturn(true);
    DocumentService documentService = mock(DocumentService.class);
    when(documentService.discoverFiles(any(), any(), any()))
        .thenThrow(new UncheckedIOException(new AccessDeniedException(dir.toString())));
    FilesystemSourceConnector connector =
        new FilesystemSourceConnector(
            allowlist, documentService, ProductionDocumentFormats.supportedFormats());

    SourceConnectionTestResult result =
        connector.testConnection(
            new SourceSettings(dir.toString(), null, null, null, false, null), null);

    assertThat(result.reachable()).isFalse();
    assertThat(result.message()).isEqualTo("Das Verzeichnis konnte nicht gelesen werden.");
  }

  @Test
  void theConnectionTestSkipsTheSameEntriesAsARun() throws IOException {
    Files.writeString(dir.resolve("aktuell.txt"), "content");
    Files.createDirectory(dir.resolve(".git"));
    Files.writeString(dir.resolve(".git").resolve("config.txt"), "content");
    Files.createDirectory(dir.resolve("Archiv"));
    Files.writeString(dir.resolve("Archiv").resolve("alt.txt"), "content");
    FilesystemSourceConnector connector = connectorWithRealWalk();
    ConnectorData patterns = ConnectorData.of(Map.of("excludePatterns", List.of("Archiv/**")));

    SourceConnectionTestResult requested =
        connector.testConnection(
            new SourceSettings(dir.toString(), null, null, null, false, patterns), null);
    SourceConnectionTestResult stored =
        connector.testConnection(
            new SourceSettings(dir.toString(), null, null, null, false, null), patterns);
    SourceConnectionTestResult defaultsOnly =
        connector.testConnection(
            new SourceSettings(dir.toString(), null, null, null, false, null), null);

    assertThat(requested.message()).isEqualTo("Verzeichnis erreichbar, 1 Dokument gefunden.");
    assertThat(stored.message()).isEqualTo("Verzeichnis erreichbar, 1 Dokument gefunden.");
    assertThat(defaultsOnly.message()).isEqualTo("Verzeichnis erreichbar, 2 Dokumente gefunden.");
  }

  @Test
  void settingsWithAnInvalidPatternAreRefusedBeforeAnythingIsStored() {
    FilesystemSourceConnector connector = connectorWithRealWalk();

    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(Map.of("excludePatterns", List.of("{Archiv")))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("sourceSettings: Ausschlussmuster: „{Archiv“ ist kein gültiges Glob-Muster.");
  }

  private FilesystemSourceConnector connectorWithRealWalk() {
    FilesystemPathAllowlist allowlist = mock(FilesystemPathAllowlist.class);
    when(allowlist.isConfigured()).thenReturn(true);
    when(allowlist.isAllowed(dir.toString())).thenReturn(true);
    return new FilesystemSourceConnector(
        allowlist, new DocumentService(), ProductionDocumentFormats.supportedFormats());
  }
}
