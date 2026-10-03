package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.FilesystemPathAllowlist;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.test.UnreadableDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemSourceConnectorTest {

  @TempDir Path dir;

  @Test
  void aDirectoryThatCannotBeOpenedIsReportedAsUnreadableNotThrown() throws IOException {
    Path locked = Files.createDirectory(dir.resolve("gesperrt"));
    FilesystemPathAllowlist allowlist = mock(FilesystemPathAllowlist.class);
    when(allowlist.isConfigured()).thenReturn(true);
    when(allowlist.isAllowed(locked.toString())).thenReturn(true);
    FilesystemSourceConnector connector = new FilesystemSourceConnector(allowlist);

    SourceConnectionTestResult result;
    try (UnreadableDirectory ignored = UnreadableDirectory.of(locked)) {
      result =
          connector.testConnection(
              new SourceSettings(locked.toString(), null, null, null, false, null), null);
    }

    assertThat(result.reachable()).isFalse();
    assertThat(result.documentCount()).isNull();
    assertThat(result.message())
        .isIn(
            "Das Verzeichnis ist für den Server nicht lesbar.",
            "Das Verzeichnis konnte nicht gelesen werden.");
  }

  @Test
  void settingsWithAnInvalidPatternAreRefusedBeforeAnythingIsStored() {
    FilesystemSourceConnector connector =
        new FilesystemSourceConnector(mock(FilesystemPathAllowlist.class));

    assertThatThrownBy(
            () ->
                connector.readSettings(
                    ConnectorData.of(Map.of("excludePatterns", List.of("{Archiv")))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("sourceSettings: Ausschlussmuster: „{Archiv“ ist kein gültiges Glob-Muster.");
  }

  @Test
  void validPatternsAreReadIntoTheirNormalisedForm() {
    FilesystemSourceConnector connector =
        new FilesystemSourceConnector(mock(FilesystemPathAllowlist.class));

    assertThat(
            connector
                .readSettings(ConnectorData.of(Map.of("excludePatterns", List.of(" Archiv/** "))))
                .asMap())
        .isEqualTo(Map.of("excludePatterns", List.of("Archiv/**")));
  }
}
