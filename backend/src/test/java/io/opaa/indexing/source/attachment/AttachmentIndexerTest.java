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
import io.opaa.indexing.AttachmentOutcome;
import io.opaa.indexing.AttachmentProgressSink;
import io.opaa.indexing.DocumentIngests;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingEventCategory;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunEventRepository;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.SourceDocumentContext;
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
 * This class carries no direct RSS dependency.
 */
class AttachmentIndexerTest {

  @TempDir Path tempDir;

  private BoundedDownloader attachmentDownloader;
  private FileProcessingService fileProcessingService;
  private IndexingJobService indexingJobService;
  private IndexingRunEventRepository indexingRunEventRepository;
  private AttachmentIndexer indexer;
  private RssFeedRunContext ctx;
  private AttachmentLimits limits;
  private UUID parentDocumentId;

  @BeforeEach
  void setUp() throws Exception {
    attachmentDownloader = mock(BoundedDownloader.class);
    fileProcessingService = mock(FileProcessingService.class);
    LibraryStorageQuotaService storageQuotaService = mock(LibraryStorageQuotaService.class);
    limits = new AttachmentLimits(10, 5_242_880L);
    indexer =
        new AttachmentIndexer(
            attachmentDownloader,
            fileProcessingService,
            storageQuotaService,
            new AttachmentProperties(5, 0, 0));
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
    // unlike AsyncIndexingExecutor/UrlIndexingExecutor/RssFeedIndexingExecutor's own
    // entry-level FAILED handling (recordFailed() + ERROR event), an attachment is not a discrete
    // unit of the run's own processed/failed/skipped counters - so FAILED here must only defer the
    // feed's ETag persistence (anyEntryDeferred) and log an ERROR event, never call recordFailed().
    Path downloaded = tempDir.resolve("attachment.txt");
    Files.writeString(downloaded, "content");
    when(attachmentDownloader.downloadBounded(any(), anyString(), anyString(), anyLong(), any()))
        .thenReturn(new BoundedDownloader.DownloadedFile(downloaded, "text/plain"));
    when(fileProcessingService.ingest(DocumentIngests.anyFile(), any()))
        .thenReturn(FileProcessingResult.FAILED);

    List<String> indexed =
        indexer.indexAll(
            ctx,
            List.of(
                new AttachmentSource.Download(
                    "https://example.org/attachment.txt",
                    "attachment.txt",
                    HttpClient.newHttpClient(),
                    null,
                    0)),
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
    // recordIndexedAttachment must also fire on the failure branches.
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
    AttachmentProgressSink progress = mock(AttachmentProgressSink.class);
    when(access.progress()).thenReturn(progress);
    Path extracted = tempDir.resolve("anlage.txt");
    Files.writeString(extracted, "Anhangsinhalt");
    when(fileProcessingService.ingest(DocumentIngests.anyFile(), any()))
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

    // Not part of the created/confirmed return value - but reported as present, and counted as
    // failed: an attempt was made and is retried next time.
    assertThat(indexed).isEmpty();
    verify(access).recordIndexedAttachment("/mail.eml/0/anlage.txt", false);
    verify(access).markDeferred();
    verify(progress).recordAttachment(AttachmentOutcome.FAILED);
  }

  @Test
  void theSameConfiguredDepthGovernsBothMailInMailAndFeedAttachmentChains()
      throws IOException, InterruptedException {
    // the recursion-depth cutoff is AttachmentProperties', one value AttachmentIndexer applies
    // regardless of which connector's own AttachmentLimits (count, size) a given call carries.
    AttachmentIndexer shallowIndexer =
        new AttachmentIndexer(
            attachmentDownloader,
            fileProcessingService,
            mock(LibraryStorageQuotaService.class),
            new AttachmentProperties(1, 0, 0));

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
        .ingest(DocumentIngests.anyFile(), any());

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
    // AttachmentLimits (its own count/size), the same AttachmentProperties.maxDepth() cutoff.
    AttachmentLimits feedLimits = new AttachmentLimits(20, 1_048_576L);
    Path outerFeedFile = tempDir.resolve("aussen.txt");
    Files.writeString(outerFeedFile, "outer feed content");
    when(attachmentDownloader.downloadBounded(any(), anyString(), anyString(), anyLong(), any()))
        .thenReturn(new BoundedDownloader.DownloadedFile(outerFeedFile, "text/plain"));
    AttachmentSource.Download nestedFeedSource =
        new AttachmentSource.Download(
            "https://example.org/innen.txt", "innen.txt", HttpClient.newHttpClient(), null, 0);
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
        .ingest(DocumentIngests.anyFile(), any());

    List<String> feedIndexed =
        shallowIndexer.indexAll(
            ctx,
            List.of(
                new AttachmentSource.Download(
                    "https://example.org/aussen.txt",
                    "aussen.txt",
                    HttpClient.newHttpClient(),
                    null,
                    0)),
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
  void aLocalFileCarriesItsSourcesVersionAndTheAccessCarriesTheParentsContext() throws IOException {
    // a Confluence attachment reaches this path as a LocalFile (the edition-aware client
    // downloaded it) - its version must land in last_modified_remote so the executor's
    // pre-download check can skip it next run, and the page's context travels via the access.
    KnowledgeLibrary confluenceLibrary =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Wiki",
            null,
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.CONFLUENCE,
            null,
            "https://wiki.example",
            null,
            null,
            false);
    SourceDocumentContext pageContext = new SourceDocumentContext("ENG", "Handbuch / Kapitel 1");
    AttachmentAccess access = mock(AttachmentAccess.class);
    when(access.targetLibrary()).thenReturn(confluenceLibrary);
    when(access.events()).thenReturn((category, message, reference) -> {});
    AttachmentProgressSink progress = mock(AttachmentProgressSink.class);
    when(access.progress()).thenReturn(progress);
    when(access.sourceContext()).thenReturn(pageContext);
    Path downloaded = tempDir.resolve("notizen.txt");
    Files.writeString(downloaded, "Notizen");
    when(fileProcessingService.ingest(DocumentIngests.anyFile(), any()))
        .thenReturn(FileProcessingResult.PROCESSED);

    List<String> indexed =
        indexer.indexAll(
            access,
            List.of(
                new AttachmentSource.LocalFile(
                    downloaded,
                    "notizen.txt",
                    "https://wiki.example/download/900/notizen.txt",
                    "3")),
            parentDocumentId,
            "https://wiki.example/pages/102",
            DocumentSourceType.CONFLUENCE,
            limits);

    assertThat(indexed).containsExactly("https://wiki.example/download/900/notizen.txt");
    verify(fileProcessingService)
        .ingest(
            DocumentIngests.that()
                .file()
                .file(downloaded)
                .named("notizen.txt")
                .at("https://wiki.example/download/900/notizen.txt")
                .marked("3")
                .sized(7L)
                .in(confluenceLibrary)
                .from(DocumentSourceType.CONFLUENCE)
                .foundOn("https://wiki.example/pages/102")
                .childOf(parentDocumentId)
                .match(),
            eq(access));
    verify(access).recordIndexedAttachment("https://wiki.example/download/900/notizen.txt", true);
    verify(progress).recordAttachment(AttachmentOutcome.PROCESSED);
  }

  @Test
  void everyOutcomeOfALocalAttachmentIsCountedExactlyOnce() throws IOException {
    // The attachment path counts each attachment itself, so every connector's cost carries the
    // same attachment share: a document created is processed, a confirmed-unchanged or text-free
    // one skipped, a quota refusal or a failed pipeline failed.
    AttachmentAccess access = mock(AttachmentAccess.class);
    when(access.targetLibrary()).thenReturn(ctx.targetLibrary());
    when(access.events()).thenReturn((category, message, reference) -> {});
    AttachmentProgressSink progress = mock(AttachmentProgressSink.class);
    when(access.progress()).thenReturn(progress);
    Path file = tempDir.resolve("anlage.txt");
    Files.writeString(file, "Anhangsinhalt");
    AttachmentSource.LocalFile source =
        new AttachmentSource.LocalFile(file, "anlage.txt", "/mail.eml/0/anlage.txt");

    for (FileProcessingResult result : FileProcessingResult.values()) {
      when(fileProcessingService.ingest(DocumentIngests.anyFile(), any())).thenReturn(result);
      indexer.indexAll(
          access,
          List.of(source),
          parentDocumentId,
          "/mail.eml",
          DocumentSourceType.FILESYSTEM,
          limits);
    }

    verify(progress, org.mockito.Mockito.times(1)).recordAttachment(AttachmentOutcome.PROCESSED);
    verify(progress, org.mockito.Mockito.times(2)).recordAttachment(AttachmentOutcome.SKIPPED);
    verify(progress, org.mockito.Mockito.times(2)).recordAttachment(AttachmentOutcome.FAILED);
    verify(access, org.mockito.Mockito.times(5))
        .recordIndexedAttachment(eq("/mail.eml/0/anlage.txt"), anyBoolean());
  }

  @Test
  void anUnsupportedLocalAttachmentIsCountedAsSkipped() throws IOException {
    AttachmentAccess access = mock(AttachmentAccess.class);
    when(access.targetLibrary()).thenReturn(ctx.targetLibrary());
    when(access.events()).thenReturn((category, message, reference) -> {});
    AttachmentProgressSink progress = mock(AttachmentProgressSink.class);
    when(access.progress()).thenReturn(progress);
    Path file = tempDir.resolve("werkzeug.exe");
    Files.write(file, new byte[] {0x4d, 0x5a, 0, 0, 1, 2});

    indexer.indexAll(
        access,
        List.of(new AttachmentSource.LocalFile(file, "werkzeug.exe", "/mail.eml/0/werkzeug.exe")),
        parentDocumentId,
        "/mail.eml",
        DocumentSourceType.FILESYSTEM,
        limits);

    verify(progress).recordAttachment(AttachmentOutcome.SKIPPED);
    verifyNoInteractions(fileProcessingService);
  }
}
