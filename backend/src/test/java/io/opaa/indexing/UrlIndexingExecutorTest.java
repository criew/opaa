package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UrlIndexingExecutorTest {

  private final DocumentRepository documentRepository = mock(DocumentRepository.class);
  private final UrlIndexingExecutor executor =
      new UrlIndexingExecutor(
          mock(AutoindexCrawlerService.class),
          mock(BoundedDownloader.class),
          mock(FileProcessingService.class),
          mock(IndexingJobService.class),
          documentRepository,
          mock(IndexingRunEventRepository.class),
          mock(LibraryStorageQuotaService.class));

  // --- #550 review: blank lastModified must never be treated as "unchanged" -----------------

  @Test
  void isUnchanged_treatsBlankLastModifiedAsUnknownAndAlwaysRefetches() {
    // The <ul>-based layouts (Apache -FancyIndexing, Python http.server) never report a
    // lastModified at all - two blank strings comparing equal would mean such a source is
    // fetched once and never again, no matter how the remote file changes.
    KnowledgeLibrary targetLibrary = libraryWithId(UUID.randomUUID());
    Document existing = mock(Document.class);
    when(existing.getLastModifiedRemote()).thenReturn("");
    when(existing.getStatus()).thenReturn(DocumentStatus.INDEXED);
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://host/file.txt"))
        .thenReturn(Optional.of(existing));

    assertThat(executor.isUnchanged("https://host/file.txt", "", targetLibrary)).isFalse();
  }

  @Test
  void isUnchanged_treatsNullLastModifiedAsUnknownAndAlwaysRefetches() {
    KnowledgeLibrary targetLibrary = libraryWithId(UUID.randomUUID());
    Document existing = mock(Document.class);
    when(existing.getLastModifiedRemote()).thenReturn(null);
    when(existing.getStatus()).thenReturn(DocumentStatus.INDEXED);
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://host/file.txt"))
        .thenReturn(Optional.of(existing));

    assertThat(executor.isUnchanged("https://host/file.txt", null, targetLibrary)).isFalse();
  }

  @Test
  void isUnchanged_returnsTrueForAMatchingNonBlankLastModified() {
    UUID libraryId = UUID.randomUUID();
    KnowledgeLibrary targetLibrary = libraryWithId(libraryId);
    Document existing = mock(Document.class);
    when(existing.getLastModifiedRemote()).thenReturn("2025-06-14 09:00");
    when(existing.getStatus()).thenReturn(DocumentStatus.INDEXED);
    when(documentRepository.findByLibraryIdAndFilePath(libraryId, "https://host/file.txt"))
        .thenReturn(Optional.of(existing));

    assertThat(executor.isUnchanged("https://host/file.txt", "2025-06-14 09:00", targetLibrary))
        .isTrue();
  }

  // --- #877 (Epic #826, Befund B6): the lookup itself is scoped to the target library ---------

  @Test
  void isUnchanged_returnsFalseWhenTargetLibraryDiffersFromTheExistingDocuments() {
    // A run indexing the same source into a different library must not skip the document just
    // because its lastModified is unchanged there - findByLibraryIdAndFilePath is scoped to
    // libraryB, so libraryA's existing document is simply never found here, unlike the pre-#877
    // global findByFilePath lookup this test used to exercise a library-equality check against.
    // libraryA's own document is rebuilt and stubbed here (not just libraryB's empty lookup) so
    // this test would fail loudly if the executor ever queried the wrong library id.
    KnowledgeLibrary libraryA = libraryWithId(UUID.randomUUID());
    KnowledgeLibrary libraryB = libraryWithId(UUID.randomUUID());
    Document existingInLibraryA = mock(Document.class);
    when(existingInLibraryA.getLastModifiedRemote()).thenReturn("2025-06-14 09:00");
    when(existingInLibraryA.getStatus()).thenReturn(DocumentStatus.INDEXED);
    when(documentRepository.findByLibraryIdAndFilePath(libraryA.getId(), "https://host/file.txt"))
        .thenReturn(Optional.of(existingInLibraryA));
    when(documentRepository.findByLibraryIdAndFilePath(libraryB.getId(), "https://host/file.txt"))
        .thenReturn(Optional.empty());

    assertThat(executor.isUnchanged("https://host/file.txt", "2025-06-14 09:00", libraryB))
        .isFalse();
  }

  private static KnowledgeLibrary libraryWithId(UUID id) {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    when(library.getId()).thenReturn(id);
    return library;
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

  // --- #267: SSRF target validation, wired through a real crawler/downloader -----------------

  @Test
  void aRunAgainstALoopbackTargetFailsWithAGermanSsrfMessageWhenValidationIsEnabled() {
    TargetAddressValidator enabledValidator = new TargetAddressValidator(true, List.of());
    IndexingJobService jobService = mock(IndexingJobService.class);
    UrlIndexingExecutor executorWithRealCrawler =
        new UrlIndexingExecutor(
            new AutoindexCrawlerService(enabledValidator),
            new BoundedDownloader(enabledValidator),
            mock(FileProcessingService.class),
            jobService,
            documentRepository,
            mock(IndexingRunEventRepository.class),
            mock(LibraryStorageQuotaService.class));
    UUID jobId = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            // Loopback - never reachable from outside the server itself, exactly the class of
            // target #267 exists to reject.
            "http://127.0.0.1:1/dir/",
            null,
            null,
            false);

    executorWithRealCrawler.execute(jobId, library);

    verify(jobService, timeout(2000))
        .failJob(
            eq(jobId),
            argThat(message -> message != null && message.contains("gesperrten Adressbereich")));
  }
}
