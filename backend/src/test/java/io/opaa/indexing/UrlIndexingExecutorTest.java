package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class UrlIndexingExecutorTest {

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final UrlIndexingExecutor executor =
      new UrlIndexingExecutor(
          mock(AutoindexCrawlerService.class),
          mock(UrlFileDownloader.class),
          mock(FileProcessingService.class),
          mock(IndexingJobService.class),
          documentRepository,
          mock(IndexingRunEventRepository.class));

  // --- #550 review: blank lastModified must never be treated as "unchanged" -----------------

  @Test
  void isUnchanged_treatsBlankLastModifiedAsUnknownAndAlwaysRefetches() {
    // The <ul>-based layouts (Apache -FancyIndexing, Python http.server) never report a
    // lastModified at all - two blank strings comparing equal would mean such a source is
    // fetched once and never again, no matter how the remote file changes.
    Document existing = mock(Document.class);
    when(existing.getLastModifiedRemote()).thenReturn("");
    when(existing.getStatus()).thenReturn(DocumentStatus.INDEXED);
    when(documentRepository.findByFilePath("https://host/file.txt"))
        .thenReturn(Optional.of(existing));

    assertThat(executor.isUnchanged("https://host/file.txt", "")).isFalse();
  }

  @Test
  void isUnchanged_treatsNullLastModifiedAsUnknownAndAlwaysRefetches() {
    Document existing = mock(Document.class);
    when(existing.getLastModifiedRemote()).thenReturn(null);
    when(existing.getStatus()).thenReturn(DocumentStatus.INDEXED);
    when(documentRepository.findByFilePath("https://host/file.txt"))
        .thenReturn(Optional.of(existing));

    assertThat(executor.isUnchanged("https://host/file.txt", null)).isFalse();
  }

  @Test
  void isUnchanged_returnsTrueForAMatchingNonBlankLastModified() {
    Document existing = mock(Document.class);
    when(existing.getLastModifiedRemote()).thenReturn("2025-06-14 09:00");
    when(existing.getStatus()).thenReturn(DocumentStatus.INDEXED);
    when(documentRepository.findByFilePath("https://host/file.txt"))
        .thenReturn(Optional.of(existing));

    assertThat(executor.isUnchanged("https://host/file.txt", "2025-06-14 09:00")).isTrue();
  }

  @Test
  void hasFileExtension_returnsTrueForUrlsWithExtension() {
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/files/report.pdf"))
        .isTrue();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/docs/readme.txt"))
        .isTrue();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/archive.tar.gz")).isTrue();
  }

  @Test
  void hasFileExtension_returnsFalseForDirectoryUrls() {
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/files/")).isFalse();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/docs")).isFalse();
  }

  @Test
  void hasFileExtension_stripsQueryStringBeforeChecking() {
    assertThat(
            UrlIndexingExecutor.hasFileExtension("https://example.com/files?sort=name&order=asc"))
        .isFalse();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/report.pdf?token=abc123"))
        .isTrue();
  }

  @Test
  void hasFileExtension_doesNotThrowOnVeryLongUrl() {
    String longParam = "x".repeat(100_000);
    String longUrl = "https://example.com/files?" + longParam;
    assertThat(UrlIndexingExecutor.hasFileExtension(longUrl)).isFalse();
  }

  @Test
  void hasFileExtension_stripsFragmentBeforeChecking() {
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/docs#section")).isFalse();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/doc.pdf#page=2")).isTrue();
  }
}
