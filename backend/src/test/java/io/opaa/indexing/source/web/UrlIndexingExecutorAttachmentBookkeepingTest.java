package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.StaleDocumentCleanupService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Unit-level coverage of {@link UrlIndexingExecutor}'s attachment bookkeeping for the run frame's
 * reconciliation (ADR-0022 Entscheidung 3) - the HTTP_DIRECTORY mirror of {@code
 * AsyncIndexingExecutorTest}'s attachment cases, with {@link AutoindexCrawlerService}/{@link
 * BoundedDownloader} mocked the way {@code UrlIndexingExecutorQuotaTest} already does and the
 * reconciliation a spy over the real service.
 */
class UrlIndexingExecutorAttachmentBookkeepingTest {

  private static final String MAIL_URL = "https://example.com/docs/mail.eml";

  @TempDir Path tempDir;

  private FileProcessingService fileProcessingService;
  private DocumentRepository documentRepository;
  private StaleDocumentCleanupService staleDocumentCleanupService;
  private UrlIndexingExecutor executor;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws IOException, InterruptedException {
    AutoindexCrawlerService crawlerService = mock(AutoindexCrawlerService.class);
    BoundedDownloader downloader = mock(BoundedDownloader.class);
    fileProcessingService = mock(FileProcessingService.class);
    documentRepository = mock(DocumentRepository.class);
    staleDocumentCleanupService =
        spy(new StaleDocumentCleanupService(documentRepository, mock(VectorChunkStore.class)));
    when(documentRepository.findByLibraryIdAndFilePath(any(), anyString()))
        .thenReturn(Optional.empty());

    library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Webverzeichnis",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            "https://example.com/docs/",
            null,
            null,
            false);

    var entry =
        new AutoindexCrawlerService.CrawledFileEntry("mail.eml", MAIL_URL, null, "1", "FILE", 0);
    when(crawlerService.crawl(anyString(), any(), anyInt(), any(), any(), anyBoolean()))
        .thenReturn(
            new AutoindexCrawlerService.CrawlResult(
                List.of(entry), false, false, false, List.of()));

    Path downloaded = tempDir.resolve("mail.eml");
    Files.writeString(downloaded, "From: a@example.org\r\n\r\nInhalt");
    when(downloader.download(any(HttpClient.class), any(), anyString(), anyString(), anyLong()))
        .thenReturn(downloaded);
    when(downloader.downloadPrefix(any(HttpClient.class), any(), anyString(), anyInt()))
        .thenReturn("From: a@example.org\r\n\r\nInhalt".getBytes(StandardCharsets.UTF_8));

    executor =
        new UrlIndexingExecutor(
            crawlerService,
            downloader,
            fileProcessingService,
            documentRepository,
            new CrawlProperties(0, 0, 0),
            mock(io.opaa.library.LibraryFolderService.class),
            new IndexingRunTemplate(
                mock(IndexingJobService.class),
                mock(IndexingRunEventRepository.class),
                staleDocumentCleanupService,
                documentRepository,
                mock(LibraryStorageQuotaService.class)));
  }

  private void stubProcessUrlFile(org.mockito.stubbing.Answer<FileProcessingResult> answer)
      throws IOException {
    when(fileProcessingService.processUrlFile(
            any(),
            anyString(),
            anyString(),
            any(),
            anyLong(),
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            isNull(),
            isNull(),
            any()))
        .thenAnswer(answer);
  }

  @Test
  void aRemovedAttachmentOfAReprocessedMailIsCleanedUpAsVanished() throws IOException {
    // ADR-0022, Entscheidung 3: for a mail actually re-parsed this run, only the attachments the
    // attachment path re-reported count as present - a bestand row of a since-removed attachment
    // must NOT survive just because its parent's URL is still listed.
    String keptPath = MAIL_URL + "/0/behalten.pdf";
    String removedPath = MAIL_URL + "/1/entfernt.pdf";
    Document mailDoc = httpDocument("mail.eml", MAIL_URL, null);
    Document keptDoc = httpDocument("behalten.pdf", keptPath, mailDoc.getId());
    Document removedDoc = httpDocument("entfernt.pdf", removedPath, mailDoc.getId());
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.HTTP_DIRECTORY))
        .thenReturn(List.of(mailDoc, keptDoc, removedDoc));

    stubProcessUrlFile(
        invocation -> {
          AttachmentAccess access = invocation.getArgument(9);
          access.recordIndexedAttachment(keptPath, true);
          return FileProcessingResult.PROCESSED;
        });

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    Set<String> currentUrls = capturedCurrentUrls();
    assertThat(currentUrls).contains(MAIL_URL, keptPath);
    assertThat(currentUrls).doesNotContain(removedPath);
    assertThat(capturedReprocessedUrls()).contains(MAIL_URL, keptPath);
    verify(documentRepository).delete(removedDoc);
    verify(documentRepository, never()).delete(keptDoc);
    verify(documentRepository, never()).delete(mailDoc);
  }

  @Test
  void attachmentsOfAChecksumSkippedMailArePreservedRecursivelyFromTheDatabase()
      throws IOException {
    // The Nachtragsfall of ADR-0022, Entscheidung 3: an unchanged (checksum-skipped) mail is
    // never re-parsed, so it is reported present but not reprocessed - and the reconciliation
    // preserves its attachment rows, including a grandchild of a nested mail, from the database.
    String innerMailPath = MAIL_URL + "/0/weitergeleitet.eml";
    String grandchildPath = innerMailPath + "/0/anlage.pdf";
    Document mailDoc = httpDocument("mail.eml", MAIL_URL, null);
    Document innerMailDoc = httpDocument("weitergeleitet.eml", innerMailPath, mailDoc.getId());
    Document grandchildDoc = httpDocument("anlage.pdf", grandchildPath, innerMailDoc.getId());
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.HTTP_DIRECTORY))
        .thenReturn(List.of(grandchildDoc, mailDoc, innerMailDoc));

    stubProcessUrlFile(invocation -> FileProcessingResult.SKIPPED);

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    assertThat(capturedCurrentUrls()).contains(MAIL_URL);
    assertThat(capturedReprocessedUrls()).doesNotContain(MAIL_URL);
    verify(documentRepository, never()).delete(any(Document.class));
  }

  @Test
  void aTransientlyFailedAttachmentOfAReprocessedMailIsPreservedNotCleanedUp() throws IOException {
    // An attachment the attachment path reported as present-but-not-reprocessed (quota, transient
    // read error - see AttachmentIndexer#storeAttachment's recordIndexedAttachment(path, false)
    // calls) stays present, and - because it was not re-parsed - its own children are preserved
    // from the database too.
    String failedPath = MAIL_URL + "/0/voruebergehend-defekt.eml";
    String childOfFailedPath = failedPath + "/0/anlage.pdf";
    Document mailDoc = httpDocument("mail.eml", MAIL_URL, null);
    Document failedDoc = httpDocument("voruebergehend-defekt.eml", failedPath, mailDoc.getId());
    Document childDoc = httpDocument("anlage.pdf", childOfFailedPath, failedDoc.getId());
    when(documentRepository.findByLibraryIdAndSourceType(
            library.getId(), DocumentSourceType.HTTP_DIRECTORY))
        .thenReturn(List.of(mailDoc, failedDoc, childDoc));

    stubProcessUrlFile(
        invocation -> {
          AttachmentAccess access = invocation.getArgument(9);
          access.recordIndexedAttachment(failedPath, false);
          return FileProcessingResult.PROCESSED;
        });

    executor.execute(UUID.randomUUID(), library, IndexingRunMode.FULL);

    assertThat(capturedCurrentUrls()).contains(MAIL_URL, failedPath);
    assertThat(capturedReprocessedUrls()).contains(MAIL_URL).doesNotContain(failedPath);
    verify(documentRepository, never()).delete(any(Document.class));
  }

  private Document httpDocument(String fileName, String filePath, UUID parentDocumentId) {
    Document document =
        new Document(fileName, filePath, "message/rfc822", 1L, DocumentSourceType.HTTP_DIRECTORY);
    document.setLibraryId(library.getId());
    document.setParentDocumentId(parentDocumentId);
    return document;
  }

  @SuppressWarnings("unchecked")
  private Set<String> capturedCurrentUrls() {
    ArgumentCaptor<Set<String>> urlsCaptor = ArgumentCaptor.forClass(Set.class);
    verify(staleDocumentCleanupService, timeout(2000))
        .reconcile(
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            urlsCaptor.capture(),
            any(),
            any(),
            any(),
            any());
    return urlsCaptor.getValue();
  }

  @SuppressWarnings("unchecked")
  private Set<String> capturedReprocessedUrls() {
    ArgumentCaptor<Set<String>> urlsCaptor = ArgumentCaptor.forClass(Set.class);
    verify(staleDocumentCleanupService, timeout(2000))
        .reconcile(
            eq(library),
            eq(DocumentSourceType.HTTP_DIRECTORY),
            any(),
            urlsCaptor.capture(),
            any(),
            any(),
            any());
    return urlsCaptor.getValue();
  }
}
