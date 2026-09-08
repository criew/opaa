package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.indexing.maintenance.StaleDocumentCleanupService;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestPolicy;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UrlIndexingExecutorTest {

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

  // --- SSRF target validation, wired through a real crawler/downloader -----------------

  @Test
  void aRunAgainstALoopbackTargetFailsWithAGermanSsrfMessageWhenValidationIsEnabled() {
    TargetAddressValidator enabledValidator = new TargetAddressValidator(true, List.of());
    IndexingJobService jobService = mock(IndexingJobService.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    UrlIndexingExecutor executorWithRealCrawler =
        new UrlIndexingExecutor(
            new AutoindexCrawlerService(enabledValidator),
            new BoundedDownloader(enabledValidator),
            mock(DocumentIngestService.class),
            documentRepository,
            new CrawlProperties(0, 0, 0),
            mock(io.opaa.library.LibraryFolderService.class),
            SourceRequestPolicy.defaults(),
            new IndexingRunTemplate(
                jobService,
                mock(IndexingRunEventRepository.class),
                mock(StaleDocumentCleanupService.class),
                documentRepository,
                mock(LibraryStorageQuotaService.class)));
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
            // target the SSRF check rejects.
            "http://127.0.0.1:1/dir/",
            null,
            null,
            false);

    executorWithRealCrawler.execute(jobId, library, IndexingRunMode.FULL);

    verify(jobService, timeout(2000))
        .failJob(
            eq(jobId),
            argThat(message -> message != null && message.contains("gesperrten Adressbereich")));
  }
}
