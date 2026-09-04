package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.indexing.pipeline.ChunkPipelineMetadata;
import io.opaa.indexing.pipeline.DiscoveredAttachment;
import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import io.opaa.indexing.pipeline.TikaFallbackPipeline;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryProperties;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

@ExtendWith(MockitoExtension.class)
class FileProcessingServiceTest {

  @Mock private DocumentService documentService;
  @Mock private ChunkingService chunkingService;
  @Mock private DocumentRepository documentRepository;
  @Mock private VectorStore vectorStore;
  @Mock private FullTextChunkStore fullTextChunkStore;
  @Mock private org.springframework.ai.embedding.EmbeddingModel embeddingModel;
  @Mock private org.springframework.ai.embedding.BatchingStrategy batchingStrategy;
  @Mock private VectorStoreWriter vectorStoreWriter;
  private VectorChunkStore vectorChunkStore;
  @Mock private ChecksumService checksumService;
  @Mock private LibraryStorageQuotaService storageQuotaService;

  @TempDir Path tempDir;

  private FileProcessingService service;
  private SimpleMeterRegistry meterRegistry;

  // #419: an indexing run always targets a caller-chosen library, never the fixed system library
  // - lets tests assert the metadata carries the chosen library.
  private KnowledgeLibrary targetLibrary;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    vectorChunkStore =
        new VectorChunkStore(
            vectorStore, embeddingModel, batchingStrategy, vectorStoreWriter, fullTextChunkStore);
    service =
        new FileProcessingService(
            TestPipelineRegistries.fallbackOnly(documentService, chunkingService),
            documentRepository,
            vectorChunkStore,
            checksumService,
            new IndexingMetrics(meterRegistry),
            storageQuotaService,
            defaultIndexingProperties(),
            Runnable::run,
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
            new io.opaa.indexing.source.attachment.AttachmentDownloadLimits(0, 0, 0, "", 0),
            org.mockito.Mockito.mock(io.opaa.library.KnowledgeLibraryRepository.class),
            TestDocumentMetadataServices.returningEmpty());
    targetLibrary = library();
    // Default: plenty of headroom, so existing tests never trip the quota check unless they
    // explicitly stub it otherwise (see the quota-specific tests below). lenient() because most
    // tests never reach it (e.g. the "skips unchanged document" ones return before this is
    // consulted).
    lenient().when(storageQuotaService.wouldExceedQuota(any(), anyLong())).thenReturn(false);
    // Default happy-path stubs for the conditional status-transition UPDATEs (#632) - tests that
    // exercise a deletion race override these explicitly to return 0. lenient() because tests
    // that never reach the success path (e.g. the "skips unchanged document" ones) never invoke
    // them, which strict stubbing would otherwise flag as unnecessary.
    lenient()
        .when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), any(), any()))
        .thenReturn(1);
    lenient().when(documentRepository.markFailed(any(), any())).thenReturn(1);
    lenient().when(documentRepository.markFailedWithoutChunks(any(), any())).thenReturn(1);
  }

  private KnowledgeLibrary library() {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(), "Bibliothek", null, UUID.randomUUID(), LibraryVisibility.PRIVATE, false);
  }

  // embeddingConcurrency=1: every test in this class exercises the pre-#734 sequential
  // storeChunks path (a single vectorStore.add call) unless it opts into concurrency itself (see
  // EmbeddingConcurrencyTest) - Runnable::run above is therefore never actually invoked here.
  private static IndexingProperties defaultIndexingProperties() {
    return new IndexingProperties(1000, 0, 50, null, null, null, null, null, 1);
  }

  // Mirrors VectorChunkStore#deleteByDocumentId's own filter construction, so assertions here
  // compare against the actual Filter.Expression the helper builds rather than the pre-#838
  // raw delete string.
  private static Filter.Expression documentIdFilter(UUID documentId) {
    return new FilterExpressionBuilder().eq("document_id", documentId.toString()).build();
  }

  @Test
  void firstRunProcessesDocument() throws IOException {
    Path file = tempDir.resolve("new-doc.txt");
    Files.writeString(file, "some content");

    when(checksumService.computeSha256(file)).thenReturn("abc123");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), anyString(), any()))
        .thenReturn(1);

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("new-doc.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(documentService).parseDocument(file);
    verify(chunkingService).chunkDocuments(eq("new-doc.txt"), eq(parsed));
    verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());

    // The initial PENDING row is still a plain save; the final INDEXED transition is now a
    // conditional UPDATE (#632), not a second save.
    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.times(1)).save(docCaptor.capture());
    ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> checksumCaptor = ArgumentCaptor.forClass(String.class);
    verify(documentRepository)
        .markIndexedFromSource(
            idCaptor.capture(), eq(1), any(), checksumCaptor.capture(), eq(null));
    assertThat(idCaptor.getValue()).isEqualTo(docCaptor.getValue().getId());
    assertThat(checksumCaptor.getValue()).isEqualTo("abc123");
  }

  @Test
  void scanPdfWithoutExtractableTextIsRejectedInsteadOfIndexedWithZeroChunks() throws IOException {
    // regression guard for #1055: a PDF Tika can open but that carries no text layer (a scan) used
    // to sail through parsed.isEmpty() - Tika still returns a Document, just with blank text - get
    // chunked into zero chunks, and land in the bestand as INDEXED with chunkCount 0: "successful",
    // but unfindable (ingestion-pipelines.md, Teil 3, Punkt 1). It must instead be rejected with a
    // clear, German message and never reach the vector store or an INDEXED row.
    //
    // Uses a spy around a real DocumentService (only #parseDocument stubbed) instead of the class'
    // own mocked field, so the scan-detection path this test exercises runs for real against the
    // file on disk - only its magic bytes, which is enough for Tika's own content-type detection
    // (see TikaFallbackPipelineTest's identical PDF_MAGIC_BYTES fixture).
    Path file = tempDir.resolve("scan.pdf");
    Files.writeString(file, "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection");

    DocumentService realDocumentService = org.mockito.Mockito.spy(new DocumentService());
    org.mockito.Mockito.doReturn(List.of(new org.springframework.ai.document.Document("")))
        .when(realDocumentService)
        .parseDocument(file);

    FileProcessingService serviceWithRealScanDetection =
        new FileProcessingService(
            TestPipelineRegistries.fallbackOnly(realDocumentService, chunkingService),
            documentRepository,
            vectorChunkStore,
            checksumService,
            new IndexingMetrics(meterRegistry),
            storageQuotaService,
            defaultIndexingProperties(),
            Runnable::run,
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
            new io.opaa.indexing.source.attachment.AttachmentDownloadLimits(0, 0, 0, "", 0),
            org.mockito.Mockito.mock(io.opaa.library.KnowledgeLibraryRepository.class),
            TestDocumentMetadataServices.returningEmpty());

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-scan");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    FileProcessingResult result = serviceWithRealScanDetection.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.NO_EXTRACTABLE_TEXT);
    // Scan detection intercepts before chunking is ever attempted - chunkDocuments is never
    // called, unlike the pre-fix path (see this test's own Javadoc for the reproduction proof).
    verify(chunkingService, never()).chunkDocuments(anyString(), any());

    // The actual bug: nothing must ever be written to the vector store or marked INDEXED with zero
    // chunks for this document.
    verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
    verify(documentRepository, never()).markIndexedFromSource(any(), anyInt(), any(), any(), any());

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository).save(docCaptor.capture());
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(documentRepository)
        .markFailedWithoutChunks(eq(docCaptor.getValue().getId()), messageCaptor.capture());
    assertThat(messageCaptor.getValue()).containsIgnoringCase("Scan");
  }

  @Test
  void chunkingProducingNoChunksIsRejectedInsteadOfIndexedWithZeroChunks() throws IOException {
    // #1090 review finding 1: TikaFallbackPipeline#isTextlessPdf only guards the pre-chunking stage
    // (blank parsed text). ChunkingService's own minChunkLengthToEmbed/minChunkSizeChars can still
    // reduce non-blank text (OCR noise, page footers) to zero chunks afterwards, and a non-PDF
    // format with blank parsed text never reaches isTextlessPdf at all (it is PDF-only). The
    // format-independent guard is on the promised outcome itself: never INDEXED with zero chunks.
    Path file = tempDir.resolve("noise-only.txt");
    Files.writeString(file, "content that survives parsing but not chunking");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-noise");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("content that is not blank"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    when(chunkingService.chunkDocuments(eq("noise-only.txt"), eq(parsed))).thenReturn(List.of());

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.NO_EXTRACTABLE_TEXT);
    verify(vectorStore, never()).add(any());
    verify(documentRepository, never()).markIndexedFromSource(any(), anyInt(), any(), any(), any());
    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository).save(docCaptor.capture());
    verify(documentRepository)
        .markFailedWithoutChunks(
            docCaptor.getValue().getId(), DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
  }

  @Test
  void processFileMarksDocumentFailedAndReportsFailedWhenThePipelineReportsNoContent()
      throws IOException {
    // #1108 review, blocker 1: NO_CONTENT (the source was readable and holds nothing) must be
    // reported the same way an uncaught pipeline exception on the same document would be -
    // FileProcessingResult#FAILED, documentsFailed incremented, never counted as processed.
    // Since #1268 a source that could not be read at all is the separate PARSE_FAILED outcome.
    Path file = tempDir.resolve("empty.txt");
    Files.writeString(file, "content the fallback pipeline reads as empty");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-empty");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(documentService.parseDocument(file)).thenReturn(List.of());

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.FAILED);
    verify(chunkingService, never()).chunkDocuments(anyString(), any());
    verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
    verify(documentRepository, never()).markIndexedFromSource(any(), anyInt(), any(), any(), any());
    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository).save(docCaptor.capture());
    verify(documentRepository).markFailedWithoutChunks(docCaptor.getValue().getId(), null);
    assertThat(
            meterRegistry.get("opaa.indexing.documents").tag("result", "failed").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void processFileSkipsWithoutPersistingWhenTheLibraryQuotaWouldBeExceeded() throws IOException {
    // #119: nothing is persisted - no document row, no chunks - once the library's quota would be
    // exceeded, and the caller (an indexing executor) learns exactly why via the distinct
    // QUOTA_EXCEEDED result, not a generic SKIPPED.
    Path file = tempDir.resolve("over-quota.txt");
    Files.writeString(file, "some content");

    when(checksumService.computeSha256(file)).thenReturn("abc123");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(storageQuotaService.wouldExceedQuota(eq(targetLibrary.getId()), anyLong()))
        .thenReturn(true);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.QUOTA_EXCEEDED);
    verify(documentRepository, never()).save(any(Document.class));
    verify(documentService, never()).parseDocument(any());
    verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
  }

  @Test
  void quotaCheckMeasuresTheSizeDeltaOfAnInPlaceUpdateNotTheFullNewSize() throws IOException {
    // #119, PR #700 review finding 5, rewritten for #1183's update-in-place contract (mirrors
    // processRssEntryChecksTheQuotaDeltaNotTheFullNewSizeWhenUpdatingInPlace): uses a REAL
    // LibraryStorageQuotaService, not a mock, so "the delta, not the full new size" is genuinely
    // exercised. The existing row is never deleted here (fk_documents_parent, see this method's
    // own Javadoc) - documentRepository.sumFileSizeByLibraryId stays at the pre-update figure
    // (900, the old document still on that path) throughout, exactly mirroring the real aggregate
    // query's own answer since no DELETE ever runs on this path.
    //
    // Quota 1000, an existing 900-byte document being replaced by a 950-byte one: checking the
    // full new size against usedBytes that still includes the old row would see 900 (old,
    // un-deleted) + 950 (full new size) = 1850 > 1000 and wrongly reject; checking the delta (the
    // actual, correct behaviour) sees 900 (old) + 50 (delta) = 950 <= 1000 and accepts. A
    // regression that checked the full new size here would flip this test's result from
    // PROCESSED to QUOTA_EXCEEDED.
    LibraryStorageQuotaService realQuotaService =
        new LibraryStorageQuotaService(documentRepository, new LibraryProperties(1000));
    FileProcessingService serviceWithRealQuota =
        new FileProcessingService(
            TestPipelineRegistries.fallbackOnly(documentService, chunkingService),
            documentRepository,
            vectorChunkStore,
            checksumService,
            new IndexingMetrics(meterRegistry),
            realQuotaService,
            defaultIndexingProperties(),
            Runnable::run,
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
            new io.opaa.indexing.source.attachment.AttachmentDownloadLimits(0, 0, 0, "", 0),
            org.mockito.Mockito.mock(io.opaa.library.KnowledgeLibraryRepository.class),
            TestDocumentMetadataServices.returningEmpty());

    Path file = tempDir.resolve("replace-under-quota.txt");
    String newContent = "x".repeat(950);
    Files.writeString(file, newContent);

    Document existingDoc =
        new Document(
            "replace-under-quota.txt", file.toAbsolutePath().toString(), "text/plain", 900L);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());
    existingDoc.setChecksum("old-checksum");
    existingDoc.setStatus(DocumentStatus.INDEXED);

    when(checksumService.computeSha256(file)).thenReturn("new-checksum");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.sumFileSizeByLibraryId(targetLibrary.getId())).thenReturn(900L);
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), any(), any()))
        .thenReturn(1);

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("replace-under-quota.txt"), eq(parsed)))
        .thenReturn(chunks);

    FileProcessingResult result = serviceWithRealQuota.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(documentRepository, never()).delete(any(Document.class));
    ArgumentCaptor<Document> savedDocCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, atLeastOnce()).save(savedDocCaptor.capture());
    assertThat(savedDocCaptor.getValue().getId()).isEqualTo(existingDoc.getId());
  }

  @Test
  void newDocumentAndItsChunksCarryTheChosenLibraryAndOrganizationAsMetadata() throws IOException {
    // #419 acceptance criteria: a run with libraryId writes every document and chunk into
    // exactly that library - checked at the document row and at the library_id chunk metadatum.
    Path file = tempDir.resolve("library-metadata.txt");
    Files.writeString(file, "some content");

    when(checksumService.computeSha256(file)).thenReturn("abc123");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks =
        List.of(
            new org.springframework.ai.document.Document(
                "chunk1", Map.of(ChunkingService.LOCATION_METADATA_KEY, "S. 2")));
    when(chunkingService.chunkDocuments(eq("library-metadata.txt"), eq(parsed))).thenReturn(chunks);

    service.processFile(file, targetLibrary);

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document savedDoc = docCaptor.getAllValues().getFirst();
    assertThat(savedDoc.getLibraryId()).isEqualTo(targetLibrary.getId());
    assertThat(savedDoc.getOrganizationId()).isEqualTo(targetLibrary.getOrganizationId());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    org.springframework.ai.document.Document storedChunk = chunkCaptor.getValue().getFirst();
    Map<String, Object> metadata = storedChunk.getMetadata();
    assertThat(metadata).containsEntry("library_id", targetLibrary.getId().toString());
    assertThat(metadata)
        .containsEntry("organization_id", targetLibrary.getOrganizationId().toString());
    // #667: the chunk's Fundort rides along to the vector store.
    assertThat(metadata).containsEntry(ChunkingService.LOCATION_METADATA_KEY, "S. 2");
    // #1056, ingestion-pipelines.md Querschnittsregel (d): every chunk names the verfahren that
    // produced it - without it, a bestand containing chunks from two pipelines is not feststellbar.
    assertThat(metadata)
        .containsEntry(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY, TikaFallbackPipeline.ID)
        .containsEntry(
            ChunkPipelineMetadata.PIPELINE_VERSION_METADATA_KEY,
            (int) TikaFallbackPipeline.VERSION);
    // #1126: the extension routing actually resolved for this document rides along too.
    assertThat(metadata)
        .containsEntry(ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY, ".txt");
  }

  @Test
  void aDocumentThatCannotBeReadForFormatDetectionWritesNoRoutingKeyAtAll() throws IOException {
    // Regression guard for the #1165 review: a transient read failure during routing (e.g. a
    // virus scanner briefly locking the file after it was discovered) must not be persisted as a
    // routing verdict - PipelineReindexService would otherwise treat it as "confirmed fallback"
    // forever instead of falling back to the pre-#1126 file-name approximation for it. Deleting
    // the file right before the re-index call reproduces the read failure inside
    // routedPipelineFor without needing to simulate an actual lock; every other collaborator that
    // would otherwise touch the file (parseDocument, chunkDocuments) is mocked.
    Path file = tempDir.resolve("bericht.pdf");
    Files.writeString(file, "some content");
    UUID documentId = UUID.randomUUID();
    Document existing = new Document("bericht.pdf", file.toString(), "application/pdf", 42L);
    existing.setLibraryId(targetLibrary.getId());
    existing.setOrganizationId(targetLibrary.getOrganizationId());
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(existing));
    when(documentRepository.markIndexed(any(), anyInt(), any())).thenReturn(1);

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("bericht.pdf"), eq(parsed))).thenReturn(chunks);

    Files.delete(file);

    boolean reindexed = service.reindexStoredDocument(documentId, file, null);

    assertThat(reindexed).isTrue();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    Map<String, Object> metadata = chunkCaptor.getValue().getFirst().getMetadata();
    assertThat(metadata).doesNotContainKey(ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY);
  }

  /**
   * A stand-in pipeline declaring an arbitrary passthrough key - stands in for e.g.
   * MailDocumentPipeline's mail_* keys without pulling that pipeline's own parsing into this
   * service-level test (#1107: the mechanism under test is generic, not tied to any one pipeline's
   * key names). {@code run} simply returns {@code chunksToReturn} - routed to via {@code
   * processRssEntry}, which calls the registry's fallback pipeline directly rather than through
   * content-based routing.
   */
  private record FakePassthroughPipeline(
      Set<String> passthroughMetadataKeys,
      List<org.springframework.ai.document.Document> chunksToReturn)
      implements DocumentPipeline {

    @Override
    public String id() {
      return "fake-passthrough";
    }

    @Override
    public short version() {
      return 1;
    }

    @Override
    public Set<String> handledFormats() {
      return Set.of();
    }

    @Override
    public DocumentPipelineResult run(DocumentPipelineSource source) {
      return DocumentPipelineResult.chunked(chunksToReturn);
    }
  }

  @Test
  void pipelineDeclaredPassthroughMetadataKeysRideAlongOnlyWhenThePipelineSetThem() {
    // #1107: storeChunks no longer hardcodes which non-bookkeeping metadata keys ride along - it
    // reads DocumentPipeline#passthroughMetadataKeys() from the pipeline that actually produced the
    // chunk. A declared-but-absent key must still be skipped, and an undeclared key present on the
    // chunk must never be copied (mirrors the pre-#1107 mail Kopfdaten test's own two assertions).
    var chunks =
        List.of(
            new org.springframework.ai.document.Document(
                "chunk1",
                Map.of(
                    "structural_key", "Kapitel 3",
                    "undeclared_key", "must not ride along")));
    var fakePipeline =
        new FakePassthroughPipeline(Set.of("structural_key", "declared_but_absent_key"), chunks);
    var registry = new DocumentPipelineRegistry(List.of(fakePipeline), fakePipeline);
    FileProcessingService serviceWithFakePipeline =
        new FileProcessingService(
            registry,
            documentRepository,
            vectorChunkStore,
            checksumService,
            new IndexingMetrics(meterRegistry),
            storageQuotaService,
            defaultIndexingProperties(),
            Runnable::run,
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
            new io.opaa.indexing.source.attachment.AttachmentDownloadLimits(0, 0, 0, "", 0),
            org.mockito.Mockito.mock(io.opaa.library.KnowledgeLibraryRepository.class),
            TestDocumentMetadataServices.returningEmpty());

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
    when(documentRepository.findByLibraryIdAndFilePath(eq(targetLibrary.getId()), anyString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    serviceWithFakePipeline.processRssEntry(
        "entry main text",
        "Titel",
        "https://example.gov/entry",
        "2025-06-15T10:30:00Z",
        targetLibrary);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    Map<String, Object> metadata = chunkCaptor.getValue().getFirst().getMetadata();
    assertThat(metadata).containsEntry("structural_key", "Kapitel 3");
    assertThat(metadata).doesNotContainKeys("declared_but_absent_key", "undeclared_key");
  }

  @Test
  void aPipelineCannotOverrideStoreChunksOwnBookkeepingKeysByDeclaringThem() {
    // A pipeline declaring one of storeChunks's own bookkeeping keys (here file_name and
    // library_id, the key the library-scoped search filter relies on) must never win over the
    // value storeChunks writes itself - the passthrough loop skips a key it already wrote before
    // ever consulting the chunk's own metadata for it.
    var chunks =
        List.of(
            new org.springframework.ai.document.Document(
                "chunk1",
                Map.of(
                    "file_name", "smuggled-name.txt", "library_id", UUID.randomUUID().toString())));
    var fakePipeline = new FakePassthroughPipeline(Set.of("file_name", "library_id"), chunks);
    var registry = new DocumentPipelineRegistry(List.of(fakePipeline), fakePipeline);
    FileProcessingService serviceWithFakePipeline =
        new FileProcessingService(
            registry,
            documentRepository,
            vectorChunkStore,
            checksumService,
            new IndexingMetrics(meterRegistry),
            storageQuotaService,
            defaultIndexingProperties(),
            Runnable::run,
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
            new io.opaa.indexing.source.attachment.AttachmentDownloadLimits(0, 0, 0, "", 0),
            org.mockito.Mockito.mock(io.opaa.library.KnowledgeLibraryRepository.class),
            TestDocumentMetadataServices.returningEmpty());

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
    when(documentRepository.findByLibraryIdAndFilePath(eq(targetLibrary.getId()), anyString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    serviceWithFakePipeline.processRssEntry(
        "entry main text",
        "Titel",
        "https://example.gov/entry",
        "2025-06-15T10:30:00Z",
        targetLibrary);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    Map<String, Object> metadata = chunkCaptor.getValue().getFirst().getMetadata();
    assertThat(metadata).containsEntry("file_name", "Titel");
    // library_id carries the permission-scoped search filter - a chunk that smuggled a different
    // value through here would leak or hide content across library boundaries.
    assertThat(metadata).containsEntry("library_id", targetLibrary.getId().toString());
  }

  private static byte[] readTestResourceBytes(String resourcePath) throws IOException {
    try (var in =
        FileProcessingServiceTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
      assertThat(in).as("Test resource %s must exist", resourcePath).isNotNull();
      return in.readAllBytes();
    }
  }

  @Test
  void aSingleChunkDocumentEmbedsByteIdenticalToBeforeIssue933() throws IOException {
    // Issue #773 (whitelist itself) and #933 review ("gesplittet ja/nein"): a document
    // ChunkingService left as a single chunk gets NO contextual-title prefix at all - see
    // FileProcessingService#storeChunks's Javadoc for why (the comic-characters eval baseline
    // regressed once every chunk, including whole unsplit documents, got prefixed). What actually
    // gets sent to the embedding call (EmbeddingModel#getEmbeddingContent(Document), defaulting to
    // Document#getFormattedContent(MetadataMode.EMBED) for org.springframework.ai.openai.
    // OpenAiEmbeddingModel, the only embedding path since #762) must therefore be byte-identical to
    // the plain chunk text, exactly as it was before #933 ever existed.
    Path file = tempDir.resolve("embed-content.txt");
    Files.writeString(file, "some content");

    when(checksumService.computeSha256(file)).thenReturn("abc123");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks =
        List.of(new org.springframework.ai.document.Document("the real chunk text to embed"));
    when(chunkingService.chunkDocuments(eq("embed-content.txt"), eq(parsed))).thenReturn(chunks);

    service.processFile(file, targetLibrary);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    org.springframework.ai.document.Document storedChunk = chunkCaptor.getValue().getFirst();

    // The metadata is still there for filtering/citation...
    assertThat(storedChunk.getMetadata()).containsKey("library_id");
    // ...the stored content column (getText()) stays exactly the chunk text, unprefixed...
    assertThat(storedChunk.getText()).isEqualTo("the real chunk text to embed");
    // ...and MetadataMode.EMBED - what an OpenAiEmbeddingModel actually sends to be embedded -
    // must be exactly the chunk text too, byte for byte: CHUNK_EMBED_CONTENT_FORMATTER_NO_PREFIX
    // is the unchanged #773 whitelist, so a single-chunk document's embedding input is
    // bit-identical
    // to before #933.
    assertThat(storedChunk.getFormattedContent(org.springframework.ai.document.MetadataMode.EMBED))
        .isEqualTo("the real chunk text to embed");
  }

  @Test
  void aMultiChunkDocumentEmbedsWithAHumanizedContextTitlePrefix() throws IOException {
    // The counterpart to the single-chunk test above: a document ChunkingService split into 2 or
    // more chunks gets every chunk prefixed with a humanized title derived from file_name (#933,
    // "Contextual Chunking") - see FileProcessingService#storeChunks's Javadoc for the split-count
    // gate and ChunkContextTitle for the title-derivation contract.
    Path file = tempDir.resolve("001_embed-content.txt");
    Files.writeString(file, "some content");

    when(checksumService.computeSha256(file)).thenReturn("abc123");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks =
        List.of(
            new org.springframework.ai.document.Document("first chunk text"),
            new org.springframework.ai.document.Document("second chunk text"));
    when(chunkingService.chunkDocuments(eq("001_embed-content.txt"), eq(parsed)))
        .thenReturn(chunks);

    service.processFile(file, targetLibrary);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    List<org.springframework.ai.document.Document> storedChunks = chunkCaptor.getValue();

    // The stored content column stays exactly the chunk text, unprefixed, for every chunk (see
    // CHUNK_EMBED_CONTENT_FORMATTER_WITH_PREFIX's own Javadoc, "Embedding-only" section)...
    assertThat(storedChunks.get(0).getText()).isEqualTo("first chunk text");
    assertThat(storedChunks.get(1).getText()).isEqualTo("second chunk text");
    // ...but MetadataMode.EMBED prefixes every one of this document's chunks identically with the
    // humanized title - "001_embed-content.txt" strips its numeric index prefix (see
    // ChunkContextTitleTest) to "embed content".
    assertThat(
            storedChunks
                .get(0)
                .getFormattedContent(org.springframework.ai.document.MetadataMode.EMBED))
        .isEqualTo("[embed content]\n\nfirst chunk text");
    assertThat(
            storedChunks
                .get(1)
                .getFormattedContent(org.springframework.ai.document.MetadataMode.EMBED))
        .isEqualTo("[embed content]\n\nsecond chunk text");
  }

  @Test
  void anUnknownMetadataKeyNeverReachesTheEmbeddingCall() throws IOException {
    // #940 review, finding 1 (and finding 2 of the follow-up review: the first version of this
    // test was tautological - storeChunks builds its own metadata map from scratch and never
    // copies a chunk's incoming metadata into it, so seeding the *input* chunk with an extra key
    // proved nothing about the formatter). The previous DefaultContentFormatter-based whitelist
    // excluded a known list of bookkeeping keys from MetadataMode.EMBED - a *blacklist* under the
    // hood (DefaultContentFormatter#metadataFilter does usableMetadataKeys.removeAll(excluded)),
    // so a metadata key added later without also being added to the exclusion list would silently
    // re-enter the embedding text (the #773 contamination this whitelist exists to prevent). The
    // per-chunk lambda formatters never read Document#getMetadata() at all - proven here by
    // mutating the metadata of the already-captured, already-formatter-attached stored chunk and
    // asserting the added key still never surfaces in getFormattedContent(EMBED).
    Path file = tempDir.resolve("001_embed-content.txt");
    Files.writeString(file, "some content");

    when(checksumService.computeSha256(file)).thenReturn("abc123");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks =
        List.of(
            new org.springframework.ai.document.Document("first chunk text"),
            new org.springframework.ai.document.Document("second chunk text"));
    when(chunkingService.chunkDocuments(eq("001_embed-content.txt"), eq(parsed)))
        .thenReturn(chunks);

    service.processFile(file, targetLibrary);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    org.springframework.ai.document.Document storedChunk = chunkCaptor.getValue().getFirst();

    storedChunk.getMetadata().put("future_bookkeeping_key", "some-future-uuid");

    assertThat(storedChunk.getFormattedContent(org.springframework.ai.document.MetadataMode.EMBED))
        .isEqualTo("[embed content]\n\nfirst chunk text")
        .doesNotContain("future_bookkeeping_key", "some-future-uuid");
  }

  @Test
  void anRssEntryEmbedsWithItsHeadlineVerbatimEvenWithAnInteriorPeriod() {
    // #940 review, finding 2: file_name for an RSS entry is a free-text headline, not a
    // filesystem-style "NNN_slug.ext" name - ChunkContextTitle#deriveTitle's extension-stripping
    // (originally lastIndexOf('.')) would truncate a headline containing a sentence-internal
    // period ("...zum 1. Januar" -> "...zum 1"). RSS entries use the headline verbatim instead of
    // running it through ChunkContextTitle at all (FileProcessingService#deriveContextTitle).
    String entryUrl = "https://example.gov/artikel/neue-regelung";
    String headline = "Neue Regelung tritt zum 1. Januar in Kraft";

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var chunks =
        List.of(
            new org.springframework.ai.document.Document("first chunk text"),
            new org.springframework.ai.document.Document("second chunk text"));
    when(chunkingService.chunkDocuments(eq(headline), any())).thenReturn(chunks);

    service.processRssEntry(
        "entry main text", headline, entryUrl, "2025-06-15T10:30:00Z", targetLibrary);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    org.springframework.ai.document.Document storedChunk = chunkCaptor.getValue().getFirst();

    assertThat(storedChunk.getFormattedContent(org.springframework.ai.document.MetadataMode.EMBED))
        .isEqualTo("[" + headline + "]\n\nfirst chunk text");
  }

  @Test
  void anRssEntryWithoutATitleGetsNoContextPrefixAtAll() {
    // #940 review, finding 2: without a feed-supplied title, file_name falls back to the entry's
    // own URL (processRssEntry) - every entry of one feed then shares a domain/path prefix, the
    // exact boilerplate-prefix pattern the #933 review found harmful for city-landmarks. A
    // URL-shaped file_name therefore gets no prefix at all, even though the document split into
    // multiple chunks.
    String entryUrl = "https://example.gov/artikel/ohne-titel";

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var chunks =
        List.of(
            new org.springframework.ai.document.Document("first chunk text"),
            new org.springframework.ai.document.Document("second chunk text"));
    when(chunkingService.chunkDocuments(eq(entryUrl), any())).thenReturn(chunks);

    service.processRssEntry(
        "entry main text", null, entryUrl, "2025-06-15T10:30:00Z", targetLibrary);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    org.springframework.ai.document.Document storedChunk = chunkCaptor.getValue().getFirst();

    assertThat(storedChunk.getFormattedContent(org.springframework.ai.document.MetadataMode.EMBED))
        .isEqualTo("first chunk text");
  }

  @Test
  void anRssAttachmentGetsAHumanizedTitleNotItsRawFileName() throws IOException {
    // #940 delta review, finding 1: an RSS entry's *attachment* also carries DocumentSourceType.
    // RSS_FEED (routed through processUrlFile by RssFeedIndexingExecutor, same as
    // processUrlFileRecordsSourceTypeAndOriginEntryForAnAttachment above), but unlike the entry's
    // own body document (processRssEntry), its file_name is a real filesystem-style name, not a
    // headline - deriving the title from source type alone would have used the raw file name
    // verbatim here (extension and numbering prefix unstripped), exactly the measured-harmful
    // pattern from #933's first measurement round. The title must therefore be humanized like any
    // other file-name-bearing document, regardless of RSS_FEED as the source type.
    Path file = tempDir.resolve("001_satzung.pdf");
    Files.writeString(file, "pdf content");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-attachment");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.gov/downloads/001_satzung.pdf"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks =
        List.of(
            new org.springframework.ai.document.Document("first chunk text"),
            new org.springframework.ai.document.Document("second chunk text"));
    when(chunkingService.chunkDocuments(eq("001_satzung.pdf"), eq(parsed))).thenReturn(chunks);

    service.processUrlFile(
        file,
        "001_satzung.pdf",
        "https://example.gov/downloads/001_satzung.pdf",
        null,
        1024,
        targetLibrary,
        DocumentSourceType.RSS_FEED,
        "https://example.gov/artikel/mein-artikel");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    org.springframework.ai.document.Document storedChunk = chunkCaptor.getValue().getFirst();

    assertThat(storedChunk.getFormattedContent(org.springframework.ai.document.MetadataMode.EMBED))
        .isEqualTo("[satzung]\n\nfirst chunk text");
  }

  @Test
  void skipsUnchangedDocumentWithSameChecksumSameLibraryAndIndexedStatus() throws IOException {
    Path file = tempDir.resolve("unchanged.txt");
    Files.writeString(file, "same content");

    when(checksumService.computeSha256(file)).thenReturn("matching-checksum");

    Document existingDoc =
        new Document("unchanged.txt", file.toAbsolutePath().toString(), null, 0L);
    existingDoc.setChecksum("matching-checksum");
    existingDoc.setStatus(DocumentStatus.INDEXED);
    existingDoc.setLibraryId(targetLibrary.getId());
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
    verify(documentService, never()).parseDocument(any());
    verify(chunkingService, never()).chunkDocuments(anyString(), any());
    verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
    verify(vectorStore, never()).delete(any(Filter.Expression.class));
  }

  @Test
  void reindexesDocumentWithChangedChecksum() throws IOException {
    Path file = tempDir.resolve("changed.txt");
    Files.writeString(file, "new content");

    when(checksumService.computeSha256(file)).thenReturn("new-checksum");

    Document existingDoc = new Document("changed.txt", file.toAbsolutePath().toString(), null, 10L);
    existingDoc.setChecksum("old-checksum");
    existingDoc.setStatus(DocumentStatus.INDEXED);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    // #667: the location key joins the bookkeeping metadata and must stay out of the embed text
    // too.
    var chunks =
        List.of(
            new org.springframework.ai.document.Document(
                "chunk1", Map.of(ChunkingService.LOCATION_METADATA_KEY, "S. 2")));
    when(chunkingService.chunkDocuments(eq("changed.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    // #1183: updated in place - only the chunks are exchanged, the row (and with it every
    // attachment's parent_document_id) survives under its own id.
    verify(vectorStore).delete(documentIdFilter(existingDoc.getId()));
    verify(documentRepository, never()).delete(any(Document.class));
    ArgumentCaptor<Document> savedDocCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, atLeastOnce()).save(savedDocCaptor.capture());
    assertThat(savedDocCaptor.getValue().getId()).isEqualTo(existingDoc.getId());
    verify(documentService).parseDocument(file);
  }

  @Test
  void aWriteFailureAfterTheOldChunksWereDeletedRemovesTheNewlyWrittenOnes() throws IOException {
    // #1268: past the delete there is no untouched previous state left to preserve, so the failure
    // path must clean up exactly as it does for a first-time document - otherwise a FAILED row
    // could keep partially written new chunks. The counterpart, a failure *before* the delete, is
    // covered by ChunkReplacementOrderIntegrationTest.
    Path file = tempDir.resolve("write-fails.txt");
    Files.writeString(file, "new content");

    when(checksumService.computeSha256(file)).thenReturn("new-checksum");

    Document existingDoc =
        new Document("write-fails.txt", file.toAbsolutePath().toString(), null, 10L);
    existingDoc.setChecksum("old-checksum");
    existingDoc.setStatus(DocumentStatus.INDEXED);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    when(chunkingService.chunkDocuments(eq("write-fails.txt"), eq(parsed)))
        .thenReturn(List.of(new org.springframework.ai.document.Document("chunk1")));
    doThrow(new IllegalStateException("vector store unavailable"))
        .when(vectorStoreWriter)
        .writeEmbeddedChunks(any(), any());

    assertThatThrownBy(() -> service.processFile(file, targetLibrary))
        .isInstanceOf(IllegalStateException.class);

    // Twice: once to make room for the new chunks, once to remove whatever the failed write left.
    verify(vectorStore, times(2)).delete(documentIdFilter(existingDoc.getId()));
    verify(documentRepository).markFailed(existingDoc.getId(), null);
  }

  @Test
  void reindexingKeepsTheLibraryAssignmentWhenTheTargetLibraryIsUnchanged() throws IOException {
    // #419 acceptance criteria: re-indexing into the same library keeps the assignment. Since
    // #1183 the row is updated in place (see reindexesDocumentWithChangedChecksum above), so this
    // pins that the updated row still carries the chosen library, not a dangling/absent one.
    Path file = tempDir.resolve("reindexed.txt");
    Files.writeString(file, "new content");

    when(checksumService.computeSha256(file)).thenReturn("new-checksum");

    Document existingDoc =
        new Document("reindexed.txt", file.toAbsolutePath().toString(), null, 10L);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());
    existingDoc.setChecksum("old-checksum");
    existingDoc.setStatus(DocumentStatus.INDEXED);
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("reindexed.txt"), eq(parsed))).thenReturn(chunks);

    service.processFile(file, targetLibrary);

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document updatedDoc = docCaptor.getAllValues().getFirst();
    assertThat(updatedDoc.getId()).isEqualTo(existingDoc.getId());
    assertThat(updatedDoc.getLibraryId()).isEqualTo(targetLibrary.getId());
    assertThat(updatedDoc.getOrganizationId()).isEqualTo(targetLibrary.getOrganizationId());
  }

  @Test
  void sameFilePathAlreadyIndexedIntoADifferentLibraryIsLeftUntouched() throws IOException {
    // #877 (Epic #826, Befund B6): identity is (library_id, file_path), not file_path alone - the
    // pre-#877 dedup lookup was global and "moved" (deleted) a document another library already
    // held the moment a second library indexed the same path. findByLibraryIdAndFilePath is scoped
    // to targetLibrary, so another library's existing document for this exact path is simply never
    // found here, and this run creates its own, independent document instead of touching it.
    Path file = tempDir.resolve("independent.txt");
    Files.writeString(file, "same path indexed into two libraries");

    KnowledgeLibrary otherLibrary = library();
    Document docInOtherLibrary =
        new Document("independent.txt", file.toAbsolutePath().toString(), null, 10L);
    docInOtherLibrary.setLibraryId(otherLibrary.getId());
    docInOtherLibrary.setChecksum("same-checksum");
    docInOtherLibrary.setStatus(DocumentStatus.INDEXED);
    // processFile only ever looks up (targetLibrary, filePath) - this stub is never actually
    // invoked by the SUT, it documents/verifies (via the never().delete() below) that a
    // pre-existing document in a different library is not what "PROCESSED" here depends on.
    lenient()
        .when(
            documentRepository.findByLibraryIdAndFilePath(
                otherLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(docInOtherLibrary));
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());

    when(checksumService.computeSha256(file)).thenReturn("same-checksum");
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("independent.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    // otherLibrary's own document and chunks for the same path are never touched by this run.
    verify(documentRepository, never()).delete(docInOtherLibrary);
    verify(vectorStore, never()).delete(any(Filter.Expression.class));

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document newDoc = docCaptor.getAllValues().getFirst();
    assertThat(newDoc.getLibraryId()).isEqualTo(targetLibrary.getId());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> chunkCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(chunkCaptor.capture(), any());
    Map<String, Object> metadata = chunkCaptor.getValue().getFirst().getMetadata();
    assertThat(metadata).containsEntry("library_id", targetLibrary.getId().toString());
  }

  @Test
  void reindexesDocumentWithNullChecksum() throws IOException {
    Path file = tempDir.resolve("legacy.txt");
    Files.writeString(file, "legacy content");

    when(checksumService.computeSha256(file)).thenReturn("computed-checksum");

    Document existingDoc = new Document("legacy.txt", file.toAbsolutePath().toString(), null, 10L);
    existingDoc.setStatus(DocumentStatus.INDEXED);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());
    // checksum is null (legacy document without checksum)
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("legacy.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(vectorStore).delete(documentIdFilter(existingDoc.getId()));
    verify(documentRepository, never()).delete(any(Document.class));
  }

  @Test
  void reindexesDocumentWithFailedStatusEvenIfChecksumMatches() throws IOException {
    Path file = tempDir.resolve("failed.txt");
    Files.writeString(file, "failed content");

    when(checksumService.computeSha256(file)).thenReturn("same-checksum");

    Document existingDoc = new Document("failed.txt", file.toAbsolutePath().toString(), null, 10L);
    existingDoc.setChecksum("same-checksum");
    existingDoc.setStatus(DocumentStatus.FAILED);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("failed.txt"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result = service.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(documentService).parseDocument(file);
  }

  @Test
  void processUrlFileIndexesNewUrlDocument() throws IOException {
    Path file = tempDir.resolve("remote-doc.pdf");
    Files.writeString(file, "pdf content");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-pdf");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.com/docs/remote-doc.pdf"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("remote-doc.pdf"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "remote-doc.pdf",
            "https://example.com/docs/remote-doc.pdf",
            "2025-06-15 10:30",
            1024,
            targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(documentService).parseDocument(file);
    verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document lastSaved = docCaptor.getAllValues().getLast();
    assertThat(lastSaved.getLibraryId()).isEqualTo(targetLibrary.getId());
    // The final INDEXED transition is a conditional UPDATE (#632), not a second save.
    verify(documentRepository)
        .markIndexedFromSource(
            eq(lastSaved.getId()), eq(1), any(), eq("sha256-of-pdf"), eq("2025-06-15 10:30"));
  }

  @Test
  void processUrlFileChunkingProducingNoChunksIsRejectedInsteadOfIndexedWithZeroChunks()
      throws IOException {
    // #1090 review finding 2: the post-chunking guard test above only covered processFile - this
    // mirrors it for processUrlFile, the second of the three ingest paths carrying the guard.
    Path file = tempDir.resolve("noise-only-remote.txt");
    Files.writeString(file, "content that survives parsing but not chunking");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-noise");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.com/docs/noise-only-remote.txt"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("content that is not blank"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    when(chunkingService.chunkDocuments(eq("noise-only-remote.txt"), eq(parsed)))
        .thenReturn(List.of());

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "noise-only-remote.txt",
            "https://example.com/docs/noise-only-remote.txt",
            null,
            1024,
            targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.NO_EXTRACTABLE_TEXT);
    verify(vectorStore, never()).add(any());
    verify(documentRepository, never()).markIndexedFromSource(any(), anyInt(), any(), any(), any());
    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository).save(docCaptor.capture());
    verify(documentRepository)
        .markFailedWithoutChunks(
            docCaptor.getValue().getId(), DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
  }

  @Test
  void processUrlFileSkipsWithoutPersistingWhenTheLibraryQuotaWouldBeExceeded() throws IOException {
    Path file = tempDir.resolve("over-quota-remote.pdf");
    Files.writeString(file, "pdf content");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-pdf");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.com/docs/over-quota-remote.pdf"))
        .thenReturn(Optional.empty());
    when(storageQuotaService.wouldExceedQuota(eq(targetLibrary.getId()), anyLong()))
        .thenReturn(true);

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "over-quota-remote.pdf",
            "https://example.com/docs/over-quota-remote.pdf",
            "2025-06-15 10:30",
            1024,
            targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.QUOTA_EXCEEDED);
    verify(documentRepository, never()).save(any(Document.class));
    verify(documentService, never()).parseDocument(any());
  }

  @Test
  void processUrlFileRecordsSourceTypeAndOriginEntryForAnAttachment() throws IOException {
    // #468: an RSS attachment goes through the same processUrlFile chain as an HTTP_DIRECTORY
    // file, but with RSS_FEED recorded as its source_type and the entry it was found on recorded
    // as source_entry_url - the trace the issue's acceptance criteria require ("Zu jeder Anlage
    // ist der Eintrag erkennbar, aus dem sie stammt").
    Path file = tempDir.resolve("anlage.pdf");
    Files.writeString(file, "pdf content");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-attachment");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.gov/downloads/anlage.pdf"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("anlage.pdf"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "anlage.pdf",
            "https://example.gov/downloads/anlage.pdf",
            null,
            1024,
            targetLibrary,
            DocumentSourceType.RSS_FEED,
            "https://example.gov/artikel/mein-artikel");

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document lastSaved = docCaptor.getAllValues().getLast();
    assertThat(lastSaved.getSourceType()).isEqualTo(DocumentSourceType.RSS_FEED);
    assertThat(lastSaved.getSourceEntryUrl()).isEqualTo("https://example.gov/artikel/mein-artikel");
  }

  @Test
  void theSameAttachmentUrlFromTwoEntriesBecomesOneDocument() throws IOException {
    // #492 review, finding 5: the previous dedup test verified two processUrlFile calls against a
    // mock - the "one document" claim actually rests on findByLibraryIdAndFilePath, exercised here
    // with a
    // stateful repository double instead of a plain call-count assertion.
    Path fileFromFirstEntry = tempDir.resolve("anlage-erster-lauf.pdf");
    Files.writeString(fileFromFirstEntry, "geteilter inhalt");
    Path fileFromSecondEntry = tempDir.resolve("anlage-zweiter-lauf.pdf");
    Files.writeString(fileFromSecondEntry, "geteilter inhalt");
    String attachmentUrl = "https://example.gov/downloads/geteilte-anlage.pdf";

    when(checksumService.computeSha256(fileFromFirstEntry)).thenReturn("sha256-geteilt");
    when(checksumService.computeSha256(fileFromSecondEntry)).thenReturn("sha256-geteilt");

    Map<String, Document> savedByFilePath = new HashMap<>();
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), attachmentUrl))
        .thenAnswer(inv -> Optional.ofNullable(savedByFilePath.get(attachmentUrl)));
    when(documentRepository.save(any(Document.class)))
        .thenAnswer(
            inv -> {
              Document doc = inv.getArgument(0);
              savedByFilePath.put(doc.getFilePath(), doc);
              return doc;
            });
    // The final INDEXED transition no longer goes through save() (#632) - the stateful double
    // has to apply it itself, the same way a real conditional UPDATE would, or the second call's
    // dedup check (checksum + INDEXED status) would never see a matching row.
    when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              Document doc =
                  savedByFilePath.values().stream()
                      .filter(d -> d.getId().equals(id))
                      .findFirst()
                      .orElseThrow();
              doc.setChunkCount(inv.getArgument(1));
              doc.setIndexedAt(inv.getArgument(2));
              doc.setChecksum(inv.getArgument(3));
              doc.setLastModifiedRemote(inv.getArgument(4));
              doc.setStatus(DocumentStatus.INDEXED);
              return 1;
            });

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(any(Path.class))).thenReturn(parsed);
    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(anyString(), eq(parsed))).thenReturn(chunks);

    FileProcessingResult firstResult =
        service.processUrlFile(
            fileFromFirstEntry,
            "anlage.pdf",
            attachmentUrl,
            null,
            17,
            targetLibrary,
            DocumentSourceType.RSS_FEED,
            "https://example.gov/artikel/erster-artikel");
    FileProcessingResult secondResult =
        service.processUrlFile(
            fileFromSecondEntry,
            "anlage.pdf",
            attachmentUrl,
            null,
            17,
            targetLibrary,
            DocumentSourceType.RSS_FEED,
            "https://example.gov/artikel/zweiter-artikel");

    assertThat(firstResult).isEqualTo(FileProcessingResult.PROCESSED);
    assertThat(secondResult).isEqualTo(FileProcessingResult.SKIPPED);
    assertThat(savedByFilePath).hasSize(1);
    Document onlyDocument = savedByFilePath.get(attachmentUrl);
    // The first entry's origin survives - the second call never touched the row again.
    assertThat(onlyDocument.getSourceEntryUrl())
        .isEqualTo("https://example.gov/artikel/erster-artikel");
    verify(documentRepository, never()).delete(any());
    verify(vectorStoreWriter, org.mockito.Mockito.times(1)).writeEmbeddedChunks(any(), any());
  }

  @Test
  void processUrlFileUsesOriginalFilenameNotTempFilename() throws IOException {
    // Reproduces: URL indexer stores temp filename (opaa-xxx.pdf) instead of original filename
    Path tempFile = Files.createTempFile(tempDir, "opaa-", ".pdf");
    Files.writeString(tempFile, "pdf content");
    String originalFileName = "my-report.pdf";

    when(checksumService.computeSha256(tempFile)).thenReturn("sha256-of-pdf");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.com/docs/my-report.pdf"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(tempFile)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq(originalFileName), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result =
        service.processUrlFile(
            tempFile,
            originalFileName,
            "https://example.com/docs/my-report.pdf",
            "2025-06-15 10:30",
            1024,
            targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document firstSaved = docCaptor.getAllValues().getFirst();
    assertThat(firstSaved.getFileName())
        .as("Document must store original filename, not temp filename")
        .isEqualTo(originalFileName);
    assertThat(firstSaved.getFileName()).doesNotStartWith("opaa-");
  }

  @Test
  void processUrlFileSkipsUnchangedDocument() throws IOException {
    Path file = tempDir.resolve("unchanged-url.pdf");
    Files.writeString(file, "pdf content");

    when(checksumService.computeSha256(file)).thenReturn("same-sha256");

    Document existingDoc =
        new Document(
            "unchanged-url.pdf",
            "https://example.com/docs/unchanged-url.pdf",
            null,
            1024L,
            DocumentSourceType.HTTP_DIRECTORY);
    existingDoc.setChecksum("same-sha256");
    existingDoc.setStatus(DocumentStatus.INDEXED);
    existingDoc.setLibraryId(targetLibrary.getId());

    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.com/docs/unchanged-url.pdf"))
        .thenReturn(Optional.of(existingDoc));

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "unchanged-url.pdf",
            "https://example.com/docs/unchanged-url.pdf",
            "2025-06-15 10:30",
            1024,
            targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
    verify(documentService, never()).parseDocument(any());
  }

  @Test
  void processUrlFileUpdatesAChangedDocumentInPlaceInsteadOfDeletingAndRecreatingIt()
      throws IOException {
    // #1183: mirrors processRssEntryUpdatesAChangedEntryInPlaceInsteadOfDeletingAndRecreatingIt -
    // a delete-and-recreate here would fail fk_documents_parent the moment this document (itself
    // possibly an attachment reprocessed via AttachmentIndexer, or a Mail-in-Mail attachment with
    // its own children) has descendant rows pointing at it via parent_document_id.
    Path file = tempDir.resolve("changed-url.pdf");
    Files.writeString(file, "new pdf content");

    when(checksumService.computeSha256(file)).thenReturn("new-sha256");

    Document existingDoc =
        new Document(
            "changed-url.pdf",
            "https://example.com/docs/changed-url.pdf",
            null,
            1024L,
            DocumentSourceType.HTTP_DIRECTORY);
    existingDoc.setChecksum("old-sha256");
    existingDoc.setStatus(DocumentStatus.INDEXED);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());

    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.com/docs/changed-url.pdf"))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("changed-url.pdf"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "changed-url.pdf",
            "https://example.com/docs/changed-url.pdf",
            "2025-06-15 10:30",
            2048,
            targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(vectorStore).delete(documentIdFilter(existingDoc.getId()));
    verify(documentRepository, never()).delete(any(Document.class));
    ArgumentCaptor<Document> savedDocCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, atLeastOnce()).save(savedDocCaptor.capture());
    assertThat(savedDocCaptor.getValue().getId()).isEqualTo(existingDoc.getId());
    verify(documentService).parseDocument(file);
  }

  @Test
  void processUrlFileRemovesOrphanedChunksWhenTheDocumentIsDeletedWhileItRuns() throws IOException {
    // Review of PR #633: FileProcessingServiceIntegrationTest exercises this window end-to-end
    // for processFile, but processUrlFile's own conditional UPDATE - DocumentRepository
    // #markIndexedFromSource - never had a unit test simulating a concurrent delete via a
    // zero-rows-updated result, the same way processUploadedFileAsync's tests do for the upload
    // path (see processUploadedFileAsyncRemovesOrphanedChunksWhenTheDocumentIsDeletedWhileItRuns
    // below).
    Path file = tempDir.resolve("deleted-mid-flight.pdf");
    Files.writeString(file, "content that outlives its own document row");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-pdf");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.com/docs/deleted-mid-flight.pdf"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), anyString(), any()))
        .thenReturn(0);

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("deleted-mid-flight.pdf"), eq(parsed)))
        .thenReturn(chunks);

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "deleted-mid-flight.pdf",
            "https://example.com/docs/deleted-mid-flight.pdf",
            "2025-06-15 10:30",
            1024,
            targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
    verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
    ArgumentCaptor<Document> savedDocCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository).save(savedDocCaptor.capture());
    verify(vectorStore).delete(documentIdFilter(savedDocCaptor.getValue().getId()));
    // The initial insert is the only save() call - the final transition never falls back to one.
    verify(documentRepository, org.mockito.Mockito.times(1)).save(any(Document.class));
    // #636 review round 2, item 2: the deletion race is counted as skipped, not silently dropped -
    // processed + failed + skipped must still sum to the number of documents seen.
    assertThat(
            meterRegistry.get("opaa.indexing.documents").tag("result", "skipped").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void processUrlFileReturnsSkippedWhenTheDocumentIsDeletedBeforeNoContentCouldBeMarkedFailed()
      throws IOException {
    Path file = tempDir.resolve("empty-url-doc.pdf");
    Files.writeString(file, "");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-empty");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), "https://example.com/docs/empty-url-doc.pdf"))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(documentRepository.markFailedWithoutChunks(any(), any())).thenReturn(0);
    when(documentService.parseDocument(file)).thenReturn(List.of());

    FileProcessingResult result =
        service.processUrlFile(
            file,
            "empty-url-doc.pdf",
            "https://example.com/docs/empty-url-doc.pdf",
            null,
            0,
            targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
    // No chunks were ever written on this path - nothing to remove from the vector store.
    verify(vectorStore, never()).delete(any(Filter.Expression.class));
    verify(chunkingService, never()).chunkDocuments(anyString(), any());
  }

  @Test
  void processRssEntryRemovesOrphanedChunksWhenTheDocumentIsDeletedWhileItRuns() {
    String entryUrl = "https://example.gov/artikel/deleted-mid-flight";

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), anyString(), any()))
        .thenReturn(0);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(anyString(), any())).thenReturn(chunks);

    FileProcessingResult result =
        service.processRssEntry(
            "entry main text", "Titel", entryUrl, "2025-06-15T10:30:00Z", targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
    verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
    ArgumentCaptor<Document> savedDocCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository).save(savedDocCaptor.capture());
    verify(vectorStore).delete(documentIdFilter(savedDocCaptor.getValue().getId()));
  }

  @Test
  void processRssEntryUpdatesAChangedEntryInPlaceInsteadOfDeletingAndRecreatingIt() {
    // A delete-and-recreate here would fail fk_documents_parent whenever the entry already has
    // attachment rows pointing at it via parent_document_id (ADR-0022, Entscheidung 4) - the row's
    // own id must survive a content change, so every existing attachment link stays valid without
    // this path having to touch attachment rows at all.
    String entryUrl = "https://example.gov/artikel/geaendert";
    Document existingDoc =
        new Document("Alter Titel", entryUrl, "text/html", 10L, DocumentSourceType.RSS_FEED);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());
    existingDoc.setChecksum("old-sha256");
    existingDoc.setStatus(DocumentStatus.INDEXED);

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("new-sha256");
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var chunks = List.of(new org.springframework.ai.document.Document("neuer Inhalt"));
    when(chunkingService.chunkDocuments(anyString(), any())).thenReturn(chunks);

    FileProcessingResult result =
        service.processRssEntry(
            "neuer Inhalt", "Neuer Titel", entryUrl, "2025-06-15T10:30:00Z", targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    verify(documentRepository, never()).delete(any(Document.class));
    ArgumentCaptor<Document> savedDocCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository).save(savedDocCaptor.capture());
    assertThat(savedDocCaptor.getValue().getId()).isEqualTo(existingDoc.getId());
    assertThat(savedDocCaptor.getValue().getFileName()).isEqualTo("Neuer Titel");
    verify(vectorStore).delete(documentIdFilter(existingDoc.getId()));
  }

  @Test
  void processRssEntryChecksTheQuotaDeltaNotTheFullNewSizeWhenUpdatingInPlace() {
    // LibraryStorageQuotaService#wouldExceedQuota's own contract expects a caller to check after
    // removing the row being replaced, so usedBytes already excludes it - the update-in-place path
    // above never removes the row, so it must pass the size delta (new minus old) instead of the
    // full new size, or a library near its quota would wrongly reject an update that nets out fine
    // (same size, or even shrinking).
    String entryUrl = "https://example.gov/artikel/quota-delta";
    Document existingDoc =
        new Document("Alter Titel", entryUrl, "text/html", 1_000L, DocumentSourceType.RSS_FEED);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());
    existingDoc.setChecksum("old-sha256");
    existingDoc.setStatus(DocumentStatus.INDEXED);

    String newContent = "neuer Inhalt"; // shorter than the old 1000-byte size
    when(checksumService.computeSha256(any(byte[].class))).thenReturn("new-sha256");
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl))
        .thenReturn(Optional.of(existingDoc));
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    var chunks = List.of(new org.springframework.ai.document.Document("neuer Inhalt"));
    when(chunkingService.chunkDocuments(anyString(), any())).thenReturn(chunks);

    service.processRssEntry(
        newContent, "Neuer Titel", entryUrl, "2025-06-15T10:30:00Z", targetLibrary);

    long expectedDelta =
        newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 1_000L;
    verify(storageQuotaService).wouldExceedQuota(targetLibrary.getId(), expectedDelta);
    verify(storageQuotaService, never())
        .wouldExceedQuota(eq(targetLibrary.getId()), longThat(value -> value != expectedDelta));
  }

  @Test
  void processRssEntryLeavesTheExistingRowUntouchedWhenTheQuotaDeltaWouldExceedIt() {
    // Before this method checked the delta up front, a QUOTA_EXCEEDED rejection here still deleted
    // the row's chunks first - the row survived (nothing recreates it, unlike processFile/
    // processUrlFile which fully delete-and-return), leaving it INDEXED with a stale checksum and
    // chunkCount but zero actual chunks in the vector store. Checking the quota before touching
    // anything means a rejection now leaves the previously working row exactly as it was.
    String entryUrl = "https://example.gov/artikel/quota-zombie";
    Document existingDoc =
        new Document("Alter Titel", entryUrl, "text/html", 10L, DocumentSourceType.RSS_FEED);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setOrganizationId(targetLibrary.getOrganizationId());
    existingDoc.setChecksum("old-sha256");
    existingDoc.setStatus(DocumentStatus.INDEXED);

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("new-sha256");
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl))
        .thenReturn(Optional.of(existingDoc));
    when(storageQuotaService.wouldExceedQuota(eq(targetLibrary.getId()), anyLong()))
        .thenReturn(true);

    FileProcessingResult result =
        service.processRssEntry(
            "neuer, groesserer Inhalt",
            "Neuer Titel",
            entryUrl,
            "2025-06-15T10:30:00Z",
            targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.QUOTA_EXCEEDED);
    verify(vectorStore, never()).delete(any(Filter.Expression.class));
    verify(documentRepository, never()).save(any(Document.class));
    // The existing row itself must still carry its own, pre-rejection state - never mutated by the
    // rejected attempt.
    assertThat(existingDoc.getChecksum()).isEqualTo("old-sha256");
    assertThat(existingDoc.getFileSize()).isEqualTo(10L);
    assertThat(existingDoc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
  }

  @Test
  void aReindexWhoseReaderThrowsLeavesTheDocumentsChunksAndStatusUntouched() throws IOException {
    // The likeliest transient failure on the re-index path: the reader throws on a damaged or
    // momentarily unreadable file, before anything has been deleted. Deleting the working chunks
    // and marking the document FAILED here would destroy a functioning document over a failure
    // that may not even repeat - and, unlike a fresh upload, there is a working previous state.
    Path file = tempDir.resolve("beschaedigt.pdf");
    Files.writeString(file, "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection");
    UUID documentId = UUID.randomUUID();
    Document existing = new Document("beschaedigt.pdf", file.toString(), "application/pdf", 42L);
    existing.setLibraryId(targetLibrary.getId());
    existing.setOrganizationId(targetLibrary.getOrganizationId());
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(existing));
    when(documentService.parseDocument(file))
        .thenThrow(new RuntimeException("Tika konnte die Datei nicht lesen"));

    boolean reindexed = service.reindexStoredDocument(documentId, file, null);

    assertThat(reindexed).isFalse();
    verify(vectorStore, never()).delete(any(Filter.Expression.class));
    verify(fullTextChunkStore, never()).deleteByDocumentId(any());
    verify(documentRepository, never()).markFailed(any(), any());
    verify(documentRepository, never()).markIndexed(any(), anyInt(), any());
  }

  @Test
  void processRssEntryRejectsAnEntryWhoseTextChunksDownToNothing() {
    // Pre-existing gap closed with #1056: this path discarded the pipeline outcome entirely and
    // left such an entry INDEXED with zero chunks - the same silent empty index the file paths
    // already guard against, only reached through a feed instead of a file.
    String entryUrl = "https://example.gov/artikel/leer";

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-empty-entry");
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    when(chunkingService.chunkDocuments(anyString(), any())).thenReturn(List.of());

    FileProcessingResult result =
        service.processRssEntry("x", "Titel", entryUrl, "2025-06-15T10:30:00Z", targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.NO_EXTRACTABLE_TEXT);
    verify(documentRepository)
        .markFailedWithoutChunks(any(), eq(DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE));
    verify(documentRepository, never()).markIndexedFromSource(any(), anyInt(), any(), any(), any());
    verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
  }

  @Test
  void processRssEntrySkipsWithoutPersistingWhenTheLibraryQuotaWouldBeExceeded() {
    String entryUrl = "https://example.gov/artikel/over-quota";

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), entryUrl))
        .thenReturn(Optional.empty());
    when(storageQuotaService.wouldExceedQuota(eq(targetLibrary.getId()), anyLong()))
        .thenReturn(true);

    FileProcessingResult result =
        service.processRssEntry(
            "entry main text", "Titel", entryUrl, "2025-06-15T10:30:00Z", targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.QUOTA_EXCEEDED);
    verify(documentRepository, never()).save(any(Document.class));
    verify(chunkingService, never()).chunkDocuments(anyString(), any());
    verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
  }

  @Test
  void processFileRemovesWrittenChunksWhenTheFinalUpdateThrows() throws IOException {
    // #636 review, item 2: the connector paths' own catch block used to mark the row FAILED
    // without ever removing chunks storeChunks had already written - the upload path's own catch
    // block (processUploadedFileAsyncMarksTheDocumentFailedAndRemovesAnyWrittenChunksWhenTheFinal
    // UpdateThrows below) already got this right; the connector paths did not.
    Path file = tempDir.resolve("fails-on-final-update.txt");
    Files.writeString(file, "content that makes it all the way to the final update");

    when(checksumService.computeSha256(file)).thenReturn("sha256-of-final-update-failure");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("fails-on-final-update.txt"), eq(parsed)))
        .thenReturn(chunks);
    when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), anyString(), any()))
        .thenThrow(new RuntimeException("final update blew up"));
    when(documentRepository.markFailed(any(), any())).thenReturn(1);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.processFile(file, targetLibrary))
        .isInstanceOf(RuntimeException.class)
        .hasMessage("final update blew up");

    // storeChunks already ran (vectorStore.add was called) before the final update failed - the
    // catch block must remove exactly those chunks, keyed by this document's id, or they become
    // orphaned.
    verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
    ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository).save(docCaptor.capture());
    verify(vectorStore).delete(documentIdFilter(docCaptor.getValue().getId()));
    verify(documentRepository)
        .markFailed(eq(docCaptor.getValue().getId()), org.mockito.ArgumentMatchers.isNull());
  }

  // #434/#589: processUploadedFile is now processUploadedFileAsync - it no longer creates or
  // deletes the document row itself. LibraryDocumentService creates the PENDING row synchronously
  // and hands this method only the row's id and the already-stored file; this method re-reads the
  // row, then transitions it via the conditional DocumentRepository#markIndexed/#markFailed
  // updates (PR #589 review, finding 1) rather than a plain entity save - see those methods'
  // Javadoc for why a save would be unsafe here.

  private Document pendingUploadDocument(String fileName) {
    Document doc =
        new Document(
            fileName,
            tempDir.resolve(fileName).toString(),
            "application/pdf",
            5L,
            DocumentSourceType.UPLOAD);
    doc.setLibraryId(UUID.randomUUID());
    doc.setOrganizationId(UUID.randomUUID());
    doc.setUploadedByUserId(UUID.randomUUID());
    doc.setChecksum("checksum-" + fileName);
    return doc;
  }

  @Test
  void processUploadedFileAsyncIndexesDocumentWithLibraryAndUploaderMetadata() throws IOException {
    Path file = tempDir.resolve("upload.pdf");
    Files.writeString(file, "uploaded pdf content");

    Document doc = pendingUploadDocument("upload.pdf");
    when(documentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));
    when(documentRepository.markIndexed(eq(doc.getId()), eq(1), any())).thenReturn(1);

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("upload.pdf"), eq(parsed))).thenReturn(chunks);

    service.processUploadedFileAsync(doc.getId(), file);

    verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
    verify(documentRepository).markIndexed(eq(doc.getId()), eq(1), any());
    verify(vectorStore, never()).delete(any(Filter.Expression.class));
  }

  @Test
  void processUploadedFileAsyncMarksTheDocumentFailedWhenNoContentIsExtracted() throws IOException {
    Path file = tempDir.resolve("empty-upload.pdf");
    Files.writeString(file, "");

    Document doc = pendingUploadDocument("empty-upload.pdf");
    when(documentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));
    when(documentService.parseDocument(file)).thenReturn(List.of());
    when(documentRepository.markFailed(
            doc.getId(), "Aus der Datei konnte kein Text extrahiert werden"))
        .thenReturn(1);

    service.processUploadedFileAsync(doc.getId(), file);

    verify(documentRepository)
        .markFailed(doc.getId(), "Aus der Datei konnte kein Text extrahiert werden");
    verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
    // Nothing was ever written for this document, so there is nothing to remove from the vector
    // store either - unlike the exception path below, which may have already written chunks.
    verify(vectorStore, never()).delete(any(Filter.Expression.class));
  }

  @Test
  void processUploadedFileAsyncMarksTheDocumentFailedForATextlessScanPdf() throws IOException {
    // #1090 review finding 4: the scan branch of the upload path had no coverage of its own.
    Path file = tempDir.resolve("scan-upload.pdf");
    Files.writeString(file, "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection");

    Document doc = pendingUploadDocument("scan-upload.pdf");
    when(documentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));
    var parsed = List.of(new org.springframework.ai.document.Document(""));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    when(documentRepository.markFailed(doc.getId(), DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE))
        .thenReturn(1);

    service.processUploadedFileAsync(doc.getId(), file);

    verify(documentRepository).markFailed(doc.getId(), DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
    verify(chunkingService, never()).chunkDocuments(anyString(), any());
    verify(vectorStore, never()).add(any());
  }

  @Test
  void processUploadedFileAsyncMarksTheDocumentFailedWhenChunkingProducesNoChunks()
      throws IOException {
    // #1090 review finding 1: the post-chunking guard applies to the upload path too, not only the
    // connector paths.
    Path file = tempDir.resolve("noise-upload.txt");
    Files.writeString(file, "content that survives parsing but not chunking");

    Document doc = pendingUploadDocument("noise-upload.txt");
    when(documentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));
    var parsed = List.of(new org.springframework.ai.document.Document("content that is not blank"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    when(chunkingService.chunkDocuments(eq("noise-upload.txt"), eq(parsed))).thenReturn(List.of());
    when(documentRepository.markFailed(doc.getId(), DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE))
        .thenReturn(1);

    service.processUploadedFileAsync(doc.getId(), file);

    verify(documentRepository).markFailed(doc.getId(), DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
    verify(vectorStore, never()).add(any());
  }

  @Test
  void processUploadedFileAsyncMarksTheDocumentFailedAndRemovesAnyWrittenChunksWhenChunkingThrows()
      throws IOException {
    Path file = tempDir.resolve("upload-that-fails-later.pdf");
    Files.writeString(file, "content that parses but fails to chunk");

    Document doc = pendingUploadDocument("upload-that-fails-later.pdf");
    when(documentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));
    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    when(chunkingService.chunkDocuments(eq("upload-that-fails-later.pdf"), eq(parsed)))
        .thenThrow(new RuntimeException("chunking blew up"));
    when(documentRepository.markFailed(doc.getId(), "Die Datei konnte nicht verarbeitet werden"))
        .thenReturn(1);

    service.processUploadedFileAsync(doc.getId(), file);

    verify(documentRepository).markFailed(doc.getId(), "Die Datei konnte nicht verarbeitet werden");
    // The catch block's vectorStore.delete call is made unconditionally, the same way
    // processFile/processUrlFile's own re-index paths always do regardless of whether there was
    // anything to remove (chunkDocuments itself threw here, before storeChunks could run).
    verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
    verify(vectorStore).delete(documentIdFilter(doc.getId()));
    // Unlike the synchronous #420 design, the row survives a failed upload - it is never deleted.
    verify(documentRepository, never()).delete(any(Document.class));
  }

  @Test
  void processUploadedFileAsyncDoesNothingWhenTheDocumentNoLongerExists() throws IOException {
    // #434: the row can be deleted (e.g. by the uploader) between the synchronous PENDING save
    // and this method actually running on uploadTaskExecutor - nothing left to update.
    Path file = tempDir.resolve("deleted-before-processing.pdf");
    Files.writeString(file, "content");
    UUID documentId = UUID.randomUUID();
    when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

    service.processUploadedFileAsync(documentId, file);

    verify(documentService, never()).parseDocument(any());
    verify(documentRepository, never()).markIndexed(any(), anyInt(), any());
    verify(documentRepository, never()).markFailed(any(), anyString());
  }

  @Test
  void processUploadedFileAsyncRemovesOrphanedChunksWhenTheDocumentIsDeletedWhileItRuns()
      throws IOException {
    // PR #589 review, finding 1 - reproduces exactly the window the fix closes: the document row
    // is deleted (e.g. by a concurrent LibraryDocumentService#deleteDocument) after this method's
    // own findById above, but before storeChunks/markIndexed run below. Simulated as a
    // zero-rows-updated result from markIndexed, the same outcome a real concurrent DELETE would
    // produce against the conditional UPDATE (DocumentRepository#markIndexed's own Javadoc) - a
    // plain entity save would instead have silently re-inserted the row as an INDEXED zombie with
    // no backing file.
    Path file = tempDir.resolve("deleted-mid-flight.pdf");
    Files.writeString(file, "content that outlives its own document row");

    Document doc = pendingUploadDocument("deleted-mid-flight.pdf");
    when(documentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));
    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("deleted-mid-flight.pdf"), eq(parsed)))
        .thenReturn(chunks);
    when(documentRepository.markIndexed(eq(doc.getId()), eq(1), any())).thenReturn(0);

    service.processUploadedFileAsync(doc.getId(), file);

    // storeChunks still wrote the chunks before the (already-stale) markIndexed call found
    // nothing to update - they are now orphaned and must be removed, or /api/v1/query would keep
    // returning them for a document that, as far as the rest of the application is concerned, no
    // longer exists. Nothing is ever marked FAILED either - the row is simply gone.
    verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
    verify(vectorStore).delete(documentIdFilter(doc.getId()));
    verify(documentRepository, never()).markFailed(any(), anyString());
    verify(documentRepository, never()).save(any());
  }

  @Test
  void
      processUploadedFileAsyncMarksTheDocumentFailedAndRemovesAnyWrittenChunksWhenTheFinalUpdateThrows()
          throws IOException {
    // The rarer failure case the same catch block also has to cover: parsing and chunking
    // succeed, storeChunks has already written chunks to the vector store, and only the update
    // that would have transitioned the row to INDEXED throws (as opposed to the finding-1 case
    // above, where it simply reports zero rows updated).
    Path file = tempDir.resolve("fails-on-final-update.pdf");
    Files.writeString(file, "content that makes it all the way to the final update");

    Document doc = pendingUploadDocument("fails-on-final-update.pdf");
    when(documentRepository.findById(doc.getId())).thenReturn(Optional.of(doc));
    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("fails-on-final-update.pdf"), eq(parsed)))
        .thenReturn(chunks);
    when(documentRepository.markIndexed(eq(doc.getId()), eq(1), any()))
        .thenThrow(new RuntimeException("final update blew up"));
    when(documentRepository.markFailed(doc.getId(), "Die Datei konnte nicht verarbeitet werden"))
        .thenReturn(1);

    service.processUploadedFileAsync(doc.getId(), file);

    // storeChunks did run (vectorStore.add was called) before the final update failed - the catch
    // block must remove exactly those chunks, keyed by this document's id, or they become
    // orphaned: still returned by /api/v1/query, unreachable through deleteDocument once nothing
    // else points at them.
    verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
    verify(vectorStore).delete(documentIdFilter(doc.getId()));
    verify(documentRepository).markFailed(doc.getId(), "Die Datei konnte nicht verarbeitet werden");
    verify(documentRepository, never()).delete(any(Document.class));
  }

  @Test
  void sameUrlAlreadyIndexedIntoADifferentLibraryIsLeftUntouched() throws IOException {
    // #877 (Epic #826, Befund B6), URL path counterpart of the filesystem test above: another
    // library already has a document for this exact URL, but findByLibraryIdAndFilePath is scoped
    // to targetLibrary and never finds it - this run creates its own, independent document.
    Path file = tempDir.resolve("independent-url.pdf");
    Files.writeString(file, "same URL indexed into two libraries");
    String remoteUrl = "https://example.com/docs/independent-url.pdf";

    KnowledgeLibrary otherLibrary = library();
    Document docInOtherLibrary =
        new Document(
            "independent-url.pdf", remoteUrl, null, 1024L, DocumentSourceType.HTTP_DIRECTORY);
    docInOtherLibrary.setLibraryId(otherLibrary.getId());
    docInOtherLibrary.setChecksum("same-sha256");
    docInOtherLibrary.setStatus(DocumentStatus.INDEXED);
    // processUrlFile only ever looks up (targetLibrary, remoteUrl) - this stub is never actually
    // invoked by the SUT, it documents/verifies (via the never().delete() below) that a
    // pre-existing document in a different library is not what "PROCESSED" here depends on.
    lenient()
        .when(documentRepository.findByLibraryIdAndFilePath(otherLibrary.getId(), remoteUrl))
        .thenReturn(Optional.of(docInOtherLibrary));
    when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), remoteUrl))
        .thenReturn(Optional.empty());

    when(checksumService.computeSha256(file)).thenReturn("same-sha256");
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    when(chunkingService.chunkDocuments(eq("independent-url.pdf"), eq(parsed))).thenReturn(chunks);

    FileProcessingResult result =
        service.processUrlFile(
            file, "independent-url.pdf", remoteUrl, "2025-06-15 10:30", 1024, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    // otherLibrary's own document and chunks for the same URL are never touched by this run.
    verify(documentRepository, never()).delete(docInOtherLibrary);
    verify(vectorStore, never()).delete(any(Filter.Expression.class));

    ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, org.mockito.Mockito.atLeast(1)).save(docCaptor.capture());
    Document newDoc = docCaptor.getAllValues().getFirst();
    assertThat(newDoc.getLibraryId()).isEqualTo(targetLibrary.getId());
  }

  /**
   * A stand-in pipeline reporting a {@link DiscoveredAttachment} - #1181 (ADR-0022, part 2): no
   * real pipeline reports one yet, so this is the only way to exercise the wiring between {@code
   * FileProcessingService} and {@code DocumentPipelineRunner}. The cleanup contract itself (outcome
   * branch, exception branch, a failing delete never turning success into failure) is covered once,
   * generically, in {@code DocumentPipelineRunnerTest}.
   */
  private record FakeDiscoveringPipeline(
      List<org.springframework.ai.document.Document> chunksToReturn,
      List<DiscoveredAttachment> discoveredAttachments)
      implements DocumentPipeline {

    @Override
    public String id() {
      return "fake-discovering";
    }

    @Override
    public short version() {
      return 1;
    }

    @Override
    public Set<String> handledFormats() {
      return Set.of();
    }

    @Override
    public DocumentPipelineResult run(DocumentPipelineSource source) {
      return DocumentPipelineResult.chunked(chunksToReturn, discoveredAttachments);
    }
  }

  @Test
  void aDiscoveredAttachmentsTempFileIsDeletedAfterProcessing() throws IOException {
    // #1181 (ADR-0022, part 2): FileProcessingService routes DocumentPipeline#run through
    // DocumentPipelineRunner, which owns deleting a reported attachment's temp file - there is no
    // attachment path yet to take that ownership instead (ADR-0022, part 3, #1182).
    Path attachmentTempFile = tempDir.resolve("discovered-attachment.tmp");
    Files.writeString(attachmentTempFile, "attachment bytes");
    var attachment = new DiscoveredAttachment("anlage.pdf", attachmentTempFile, "application/pdf");
    var chunks = List.of(new org.springframework.ai.document.Document("chunk1"));
    var fakePipeline = new FakeDiscoveringPipeline(chunks, List.of(attachment));
    var registry = new DocumentPipelineRegistry(List.of(fakePipeline), fakePipeline);
    FileProcessingService serviceWithFakePipeline =
        new FileProcessingService(
            registry,
            documentRepository,
            vectorChunkStore,
            checksumService,
            new IndexingMetrics(meterRegistry),
            storageQuotaService,
            defaultIndexingProperties(),
            Runnable::run,
            org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
            new io.opaa.indexing.source.attachment.AttachmentDownloadLimits(0, 0, 0, "", 0),
            org.mockito.Mockito.mock(io.opaa.library.KnowledgeLibraryRepository.class),
            TestDocumentMetadataServices.returningEmpty());

    when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
    when(documentRepository.findByLibraryIdAndFilePath(eq(targetLibrary.getId()), anyString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

    serviceWithFakePipeline.processRssEntry(
        "entry main text",
        "Titel",
        "https://example.gov/entry",
        "2025-06-15T10:30:00Z",
        targetLibrary);

    assertThat(Files.exists(attachmentTempFile)).isFalse();
  }
}
