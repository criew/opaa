package io.opaa.indexing.source.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.AttachmentProgressSink;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.source.rss.RssFeedRunContext;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.sourceaccess.BoundedDownloader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit-level coverage of {@link AttachmentIndexer}, split out from {@code
 * RssFeedIndexingExecutorTest}'s heavier end-to-end coverage (real HTTP server, real {@link
 * BoundedDownloader}) - here {@link BoundedDownloader} and {@link FileProcessingService} are both
 * mocked, isolating the one branch that needs its own targeted proof. Uses {@link
 * RssFeedRunContext} as its {@link AttachmentAccess} - the same context RSS itself supplies since
 * #1182 generalized this class away from a direct RSS dependency.
 */
class AttachmentIndexerTest {

  @TempDir Path tempDir;

  private BoundedDownloader attachmentDownloader;
  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private IndexingRunEventRepository indexingRunEventRepository;
  private DocumentRepository documentRepository;
  private AttachmentIndexer indexer;
  private RssFeedRunContext ctx;
  private AttachmentDownloadLimits limits;
  private UUID parentDocumentId;

  @BeforeEach
  void setUp() {
    attachmentDownloader = mock(BoundedDownloader.class);
    fileProcessingService = mock(FileProcessingService.class);
    LibraryStorageQuotaService storageQuotaService = mock(LibraryStorageQuotaService.class);
    documentRepository = mock(DocumentRepository.class);
    limits = new AttachmentDownloadLimits(10, 5_242_880L, 0, "opaa-test-agent");
    indexer =
        new AttachmentIndexer(
            attachmentDownloader,
            fileProcessingService,
            storageQuotaService,
            documentRepository,
            new AttachmentProperties(5));
    parentDocumentId = UUID.randomUUID();

    indexingJobService = mock(IndexingJobService.class);
    indexingRunEventRepository = mock(IndexingRunEventRepository.class);
    UUID jobId = UUID.randomUUID();
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.RSS_FEED,
            null,
            null,
            null,
            null,
            false);
    HttpClient httpClient = HttpClient.newHttpClient();
    ctx =
        new RssFeedRunContext(
            httpClient,
            httpClient,
            library,
            null,
            "https://example.org/feed.xml",
            new IndexingRunProgress(indexingJobService, jobId),
            new IndexingRunEventRecorder(indexingRunEventRepository, indexingJobService, jobId),
            new AtomicBoolean(false));
  }

  @Test
  void anAttachmentThePipelineCannotParseAtAllIsRecordedAsAnErrorButNeverCountedAsARunEntry()
      throws IOException, InterruptedException {
    // #1108 review: unlike AsyncIndexingExecutor/UrlIndexingExecutor/RssFeedIndexingExecutor's own
    // entry-level FAILED handling (recordFailed() + ERROR event), an attachment is not a discrete
    // unit of the run's own processed/failed/skipped counters - so FAILED here must only defer the
    // feed's ETag persistence (anyEntryDeferred) and log an ERROR event, never call recordFailed().
    Path downloaded = tempDir.resolve("attachment.txt");
    Files.writeString(downloaded, "content");
    when(attachmentDownloader.downloadBounded(
            any(), anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(new BoundedDownloader.DownloadedFile(downloaded, "text/plain"));
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.FAILED);

    List<String> indexed =
        indexer.indexAll(
            ctx,
            List.of(
                new AttachmentSource.Download(
                    "https://example.org/attachment.txt",
                    "attachment.txt",
                    HttpClient.newHttpClient(),
                    null)),
            parentDocumentId,
            "https://example.org/entry.html",
            DocumentSourceType.RSS_FEED,
            limits);

    assertThat(indexed).isEmpty();
    assertThat(ctx.anyEntryDeferred()).isTrue();
    verify(indexingRunEventRepository)
        .save(
            argThat(
                event ->
                    event.getCategory() == IndexingEventCategory.ERROR
                        && "Verarbeitung der Anlage fehlgeschlagen".equals(event.getMessage())
                        && "https://example.org/attachment.txt".equals(event.getReference())));
    // No recordFailed()/recordProcessed() call on this run's own counters - an attachment failure
    // must never call completeJob with an inflated or deflated processed/failed count of its own.
    verifyNoInteractions(indexingJobService);
  }

  @Test
  void aTransientlyFailingAttachmentIsStillReportedAsPresentAndNotLeftToVanish()
      throws IOException {
    // Review round 2, finding 1: recordIndexedAttachment must also fire on the failure branches.
    // A still-present, earlier-indexed attachment of a re-parsed parent that fails transiently
    // (quota momentarily full, temp read error) would otherwise appear in neither the recorded
    // paths nor the caller's database fold-in (the parent counts as reprocessed) - cleanupVanished
    // would delete its row permanently, and the then-unchanged parent's checksum skip would never
    // re-extract it. reprocessed=false: its own children were not freshly enumerated either.
    KnowledgeLibrary filesystemLibrary =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            tempDir.toString(),
            null,
            null,
            null,
            false);
    AttachmentAccess access = mock(AttachmentAccess.class);
    when(access.targetLibrary()).thenReturn(filesystemLibrary);
    when(access.events()).thenReturn((category, message, reference) -> {});
    Path extracted = tempDir.resolve("anlage.txt");
    Files.writeString(extracted, "Anhangsinhalt");
    when(fileProcessingService.processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(FileProcessingResult.QUOTA_EXCEEDED);

    List<String> indexed =
        indexer.indexAll(
            access,
            List.of(
                new AttachmentSource.LocalFile(extracted, "anlage.txt", "/mail.eml/0/anlage.txt")),
            parentDocumentId,
            "/mail.eml",
            DocumentSourceType.FILESYSTEM,
            limits);

    // Not part of the created/confirmed return value - but reported as present.
    assertThat(indexed).isEmpty();
    verify(access).recordIndexedAttachment("/mail.eml/0/anlage.txt", false);
    verify(access).markDeferred();
  }

  @Test
  void theSameConfiguredDepthGovernsBothMailInMailAndFeedAttachmentChains()
      throws IOException, InterruptedException {
    // #1269: the recursion-depth cutoff moved out of AttachmentDownloadLimits (a per-source,
    // per-connector record) into AttachmentProperties, one value AttachmentIndexer applies
    // regardless of which connector's own, differently-shaped AttachmentDownloadLimits (count,
    // size, politeness, user agent) a given call carries.
    AttachmentIndexer shallowIndexer =
        new AttachmentIndexer(
            attachmentDownloader,
            fileProcessingService,
            mock(LibraryStorageQuotaService.class),
            documentRepository,
            new AttachmentProperties(1));

    // Mail-in-Mail: a LocalFile attachment whose own processing reports one more nested LocalFile
    // attachment - mirrors FileProcessingService#processUrlFile routing a discovered .eml back
    // through this class on the same thread.
    KnowledgeLibrary filesystemLibrary =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            tempDir.toString(),
            null,
            null,
            null,
            false);
    AttachmentAccess mailAccess = mock(AttachmentAccess.class);
    when(mailAccess.targetLibrary()).thenReturn(filesystemLibrary);
    when(mailAccess.events()).thenReturn((category, message, reference) -> {});
    when(mailAccess.progress()).thenReturn(mock(AttachmentProgressSink.class));
    Path outerMail = tempDir.resolve("aussen.eml");
    Files.writeString(outerMail, "outer");
    Path innerMail = tempDir.resolve("innen.eml");
    Files.writeString(innerMail, "inner");
    AttachmentSource.LocalFile nestedMailSource =
        new AttachmentSource.LocalFile(innerMail, "innen.eml", "/aussen.eml/0");
    // doAnswer, not when(...).thenAnswer(...): the latter evaluates processUrlFile(...) eagerly as
    // a plain Java call, which would run whatever stub is already active (there is none here yet,
    // but the feed restubbing below would otherwise re-trigger this very answer as a side effect).
    doAnswer(
            invocation -> {
              shallowIndexer.indexAll(
                  mailAccess,
                  List.of(nestedMailSource),
                  parentDocumentId,
                  "/aussen.eml",
                  DocumentSourceType.FILESYSTEM,
                  limits);
              return FileProcessingResult.PROCESSED;
            })
        .when(fileProcessingService)
        .processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), any(), any(), any(), any(), any());

    List<String> mailIndexed =
        shallowIndexer.indexAll(
            mailAccess,
            List.of(new AttachmentSource.LocalFile(outerMail, "aussen.eml", "/parent.eml/0")),
            parentDocumentId,
            "/parent.eml",
            DocumentSourceType.FILESYSTEM,
            limits);

    assertThat(mailIndexed).hasSize(1);
    // The nested attachment at depth 1 was cut off, not indexed - proves the depth limit applied.
    verify(mailAccess).markDeferred();
    verify(mailAccess, never()).recordIndexedAttachment(eq("/aussen.eml/0"), anyBoolean());

    // Feed-Anlage: the RSS/HTTP-directory analogue, a Download attachment whose own processing
    // reports one more nested Download attachment - a different, feed-shaped
    // AttachmentDownloadLimits (its own count/size/politeness/user-agent), the same
    // AttachmentProperties.maxDepth() cutoff.
    AttachmentDownloadLimits feedLimits =
        new AttachmentDownloadLimits(20, 1_048_576L, 250, "opaa-feed-agent");
    Path outerFeedFile = tempDir.resolve("aussen.txt");
    Files.writeString(outerFeedFile, "outer feed content");
    when(attachmentDownloader.downloadBounded(
            any(), anyString(), anyString(), anyLong(), any(), any()))
        .thenReturn(new BoundedDownloader.DownloadedFile(outerFeedFile, "text/plain"));
    AttachmentSource.Download nestedFeedSource =
        new AttachmentSource.Download(
            "https://example.org/innen.txt", "innen.txt", HttpClient.newHttpClient(), null);
    // doAnswer again - restubbing processUrlFile via when(...) here would evaluate the call
    // eagerly and re-trigger the mail answer above as a side effect (the mock is still stubbed
    // with it at this point).
    doAnswer(
            invocation -> {
              shallowIndexer.indexAll(
                  ctx,
                  List.of(nestedFeedSource),
                  parentDocumentId,
                  "https://example.org/aussen.txt",
                  DocumentSourceType.RSS_FEED,
                  feedLimits);
              return FileProcessingResult.PROCESSED;
            })
        .when(fileProcessingService)
        .processUrlFile(
            any(), anyString(), anyString(), any(), anyLong(), any(), any(), any(), any(), any());

    List<String> feedIndexed =
        shallowIndexer.indexAll(
            ctx,
            List.of(
                new AttachmentSource.Download(
                    "https://example.org/aussen.txt",
                    "aussen.txt",
                    HttpClient.newHttpClient(),
                    null)),
            parentDocumentId,
            "https://example.org/entry.html",
            DocumentSourceType.RSS_FEED,
            feedLimits);

    assertThat(feedIndexed).hasSize(1);
    // Same cutoff, same depth - proven present via the shared ctx's deferred flag, exactly like
    // the mail chain's markDeferred() above.
    assertThat(ctx.anyEntryDeferred()).isTrue();
  }

  @Test
  void existingAttachmentPathsReadsThemBackByParentDocumentId() {
    // ADR-0022, Entscheidung 3's Nachtragsfall: a parent skipped as unchanged is never re-parsed,
    // so indexAll never rediscovers its attachments this run - a caller that folds attachment paths
    // into currentFilePaths must instead read the already-persisted ones back by parentDocumentId.
    Document attachmentOne =
        new Document("erste.pdf", "https://example.org/erste.pdf", "application/pdf", 10L);
    Document attachmentTwo =
        new Document("zweite.pdf", "https://example.org/zweite.pdf", "application/pdf", 20L);
    when(documentRepository.findByParentDocumentId(parentDocumentId))
        .thenReturn(List.of(attachmentOne, attachmentTwo));

    assertThat(indexer.existingAttachmentPaths(parentDocumentId))
        .containsExactlyInAnyOrder(
            "https://example.org/erste.pdf", "https://example.org/zweite.pdf");
  }
}
