package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.AutoindexCrawlerService.CrawledFileEntry;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue #375: the filesystem path ({@link DocumentService}) and the network path ({@link
 * UrlIndexingExecutor}) used to carry their own extension lists, so the same document was accepted
 * or rejected depending on how it entered the system. Nobody can predict that, and nobody can
 * explain it to whoever runs the installation.
 */
class DocumentFormatParityTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "handbuch.md",
        "notiz.txt",
        "bescheid.pdf",
        "vermerk.docx",
        "folien.pptx",
        "altes-schreiben.doc",
        "haushalt.xlsx",
        "liste.csv",
        "scan.png",
        "archiv.zip",
        "DATEI-IN-GROSSBUCHSTABEN.PDF"
      })
  void bothIndexingPathsDecideAlikeForTheSameFile(String fileName) {
    boolean acceptedFromFilesystem = new DocumentService().isSupportedFormat(Path.of(fileName));
    boolean acceptedFromNetwork =
        UrlIndexingExecutor.isSupportedFormat(
            new CrawledFileEntry(
                fileName, "https://example.invalid/" + fileName, "2026-01-01", "1k", "FILE", 0));

    assertThat(acceptedFromNetwork)
        .as(
            "'%s' must be treated identically by both indexing paths; filesystem says %s, "
                + "network says %s",
            fileName, acceptedFromFilesystem, acceptedFromNetwork)
        .isEqualTo(acceptedFromFilesystem);
  }
}
