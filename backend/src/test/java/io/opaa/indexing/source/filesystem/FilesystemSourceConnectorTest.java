package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.format.DocumentService;
import io.opaa.indexing.source.FilesystemPathAllowlist;
import io.opaa.indexing.source.SourceConnectionTestResult;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.test.ProductionDocumentFormats;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
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
    when(documentService.discoverFiles(any(), any()))
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
}
