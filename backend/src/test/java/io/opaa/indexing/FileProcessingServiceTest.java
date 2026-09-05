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
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.TikaFallbackPipeline;
import io.opaa.indexing.source.attachment.AttachmentAccess;
import io.opaa.indexing.source.attachment.AttachmentDownloadLimits;
import io.opaa.indexing.source.attachment.AttachmentIndexer;
import io.opaa.indexing.source.attachment.AttachmentSource;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryProperties;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The one ingest sequence, one test per outcome and per row state - every source shape goes through
 * {@link FileProcessingService#ingest}, so a rule proven here holds for all of them. The
 * source-specific mapping onto {@link DocumentIngest} is the executors' own business.
 */
@ExtendWith(MockitoExtension.class)
class FileProcessingServiceTest {

  private static final String ENTRY_URL = "https://example.gov/artikel/eintrag";
  private static final String PAGE_URL =
      "https://wiki.behoerde.example/pages/viewpage.action?pageId=102";
  private static final String PUBLISHED_AT = "2025-06-15T10:30:00Z";

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

  // an indexing run always targets a caller-chosen library, never the fixed system library
  // - lets tests assert the metadata carries the chosen library.
  private KnowledgeLibrary targetLibrary;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    vectorChunkStore =
        new VectorChunkStore(
            vectorStore, embeddingModel, batchingStrategy, vectorStoreWriter, fullTextChunkStore);
    service = serviceWith(TestPipelineRegistries.fallbackOnly(documentService, chunkingService));
    targetLibrary = library();
    // Default: plenty of headroom, so tests never trip the quota check unless they explicitly
    // stub it otherwise. lenient() because most tests never reach it.
    lenient().when(storageQuotaService.wouldExceedQuota(any(), anyLong())).thenReturn(false);
    // Default happy-path stubs for the conditional status-transition UPDATEs - tests that
    // exercise a deletion race override these explicitly to return 0.
    lenient()
        .when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), any(), any()))
        .thenReturn(1);
    lenient().when(documentRepository.markFailed(any(), any())).thenReturn(1);
    lenient().when(documentRepository.markFailedWithoutChunks(any(), any())).thenReturn(1);
  }

  private FileProcessingService serviceWith(DocumentPipelineRegistry registry) {
    return serviceWith(registry, storageQuotaService);
  }

  private FileProcessingService serviceWith(
      DocumentPipelineRegistry registry, LibraryStorageQuotaService quotaService) {
    return serviceWith(registry, quotaService, Mockito.mock(ObjectProvider.class));
  }

  @SuppressWarnings("unchecked")
  private FileProcessingService serviceWith(
      DocumentPipelineRegistry registry,
      LibraryStorageQuotaService quotaService,
      ObjectProvider<AttachmentIndexer> attachmentIndexerProvider) {
    return new FileProcessingService(
        registry,
        documentRepository,
        vectorChunkStore,
        checksumService,
        new IndexingMetrics(meterRegistry),
        quotaService,
        defaultIndexingProperties(),
        Runnable::run,
        attachmentIndexerProvider,
        new AttachmentDownloadLimits(0, 0, 0, ""),
        TestDocumentMetadataServices.returningEmpty());
  }

  private KnowledgeLibrary library() {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(), "Bibliothek", null, UUID.randomUUID(), LibraryVisibility.PRIVATE, false);
  }

  // embeddingConcurrency=1: every test in this class exercises the sequential storeChunks path (a
  // single vectorStore.add call) - see EmbeddingConcurrencyTest for the fan-out.
  private static IndexingProperties defaultIndexingProperties() {
    return new IndexingProperties(1000, 0, 50, null, null, null, null, 1);
  }

  // Mirrors VectorChunkStore#deleteByDocumentId's own filter construction.
  private static Filter.Expression documentIdFilter(UUID documentId) {
    return new FilterExpressionBuilder().eq("document_id", documentId.toString()).build();
  }

  private static List<org.springframework.ai.document.Document> chunks(String... texts) {
    return java.util.Arrays.stream(texts)
        .map(org.springframework.ai.document.Document::new)
        .toList();
  }

  private Path fileNamed(String name, String content) throws IOException {
    Path file = tempDir.resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private DocumentIngest localFile(Path file) throws IOException {
    return DocumentIngest.localFile(targetLibrary, file).build();
  }

  /** A first-run row: no existing document under the path, saves answer with the entity. */
  private void stubNewRow(Path file, String checksum) throws IOException {
    when(checksumService.computeSha256(file)).thenReturn(checksum);
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.empty());
    when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  private void stubParsedInto(
      Path file, List<org.springframework.ai.document.Document> chunksToReturn) {
    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);
    when(chunkingService.chunkDocuments(eq(file.getFileName().toString()), eq(parsed)))
        .thenReturn(chunksToReturn);
  }

  private Document existingIndexed(Path file, String checksum, long size) {
    Document existing =
        new Document(file.getFileName().toString(), file.toAbsolutePath().toString(), null, size);
    existing.setChecksum(checksum);
    existing.setStatus(DocumentStatus.INDEXED);
    existing.setLibraryId(targetLibrary.getId());
    existing.setOrganizationId(targetLibrary.getOrganizationId());
    lenient()
        .when(
            documentRepository.findByLibraryIdAndFilePath(
                targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existing));
    lenient()
        .when(documentRepository.save(any(Document.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    return existing;
  }

  private Document savedDocument() {
    ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository, atLeastOnce()).save(captor.capture());
    return captor.getAllValues().getFirst();
  }

  private List<org.springframework.ai.document.Document> storedChunks() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<org.springframework.ai.document.Document>> captor =
        ArgumentCaptor.forClass(List.class);
    verify(vectorStoreWriter).writeEmbeddedChunks(captor.capture(), any());
    return captor.getValue();
  }

  private double counter(String result) {
    return meterRegistry.get("opaa.indexing.documents").tag("result", result).counter().count();
  }

  @Nested
  class Outcomes {

    @Test
    void chunkedContentIsIndexedWithOneSaveAndOneConditionalUpdate() throws IOException {
      Path file = fileNamed("new-doc.txt", "some content");
      stubNewRow(file, "abc123");
      stubParsedInto(file, chunks("chunk1"));

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
      // The initial PENDING row is a plain save; the final INDEXED transition is a conditional
      // UPDATE carrying the checksum, never a second save.
      verify(documentRepository, times(1)).save(any(Document.class));
      UUID documentId = savedDocument().getId();
      verify(documentRepository)
          .markIndexedFromSource(eq(documentId), eq(1), any(), eq("abc123"), eq(null));
      assertThat(counter("processed")).isEqualTo(1.0);
    }

    @Test
    void contentThatChunksDownToNothingIsRejectedNotIndexedWithZeroChunks() throws IOException {
      // ChunkingService's own thresholds can reduce non-blank text (OCR noise, page footers) to
      // zero chunks - the guard is on the promised outcome itself: never INDEXED with zero chunks.
      Path file = fileNamed("noise-only.txt", "content that survives parsing but not chunking");
      stubNewRow(file, "sha256-of-noise");
      stubParsedInto(file, List.of());

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.NO_EXTRACTABLE_TEXT);
      verify(vectorStore, never()).add(any());
      verify(documentRepository, never())
          .markIndexedFromSource(any(), anyInt(), any(), any(), any());
      UUID documentId = savedDocument().getId();
      verify(documentRepository)
          .markFailedWithoutChunks(documentId, DocumentService.NO_EXTRACTABLE_TEXT_MESSAGE);
      assertThat(counter("skipped")).isEqualTo(1.0);
    }

    @Test
    void aSourceReadAsEmptyIsMarkedFailedAndCountedAsFailed() throws IOException {
      // NO_CONTENT (readable, holds nothing) is reported like an uncaught pipeline exception on
      // the same document: FAILED, counted as failed, never as processed.
      Path file = fileNamed("empty.txt", "content the fallback pipeline reads as empty");
      stubNewRow(file, "sha256-of-empty");
      when(documentService.parseDocument(file)).thenReturn(List.of());

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.FAILED);
      verify(chunkingService, never()).chunkDocuments(anyString(), any());
      verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
      UUID documentId = savedDocument().getId();
      verify(documentRepository)
          .markFailedWithoutChunks(documentId, FileProcessingService.NO_CONTENT_MESSAGE);
      assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    void aSourceThatCannotBeParsedKeepsItsPreviousChunksAndIsMarkedFailed() throws IOException {
      // PARSE_FAILED (mapped by DocumentPipelineRunner from whatever the pipeline throws) says
      // nothing about the new content, so the previous chunks and their count both stand.
      Path file = fileNamed("kaputt.txt", "new bytes");
      when(checksumService.computeSha256(file)).thenReturn("new-checksum");
      Document existing = existingIndexed(file, "old-checksum", 10L);
      existing.setChunkCount(3);
      when(documentService.parseDocument(file))
          .thenThrow(new RuntimeException("Tika konnte die Datei nicht lesen"));

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.FAILED);
      verify(vectorStore, never()).delete(any(Filter.Expression.class));
      verify(documentRepository)
          .markFailed(existing.getId(), FileProcessingService.PROCESSING_FAILED_MESSAGE);
      verify(documentRepository, never()).markFailedWithoutChunks(any(), any());
      assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    void anExceptionAfterTheOldChunksWereDeletedRemovesTheNewlyWrittenOnes() throws IOException {
      // Past the delete there is no untouched previous state left to preserve, so the failure
      // path must clean up exactly as for a first-time document - a FAILED row never keeps
      // partially written new chunks, and its count says so.
      Path file = fileNamed("write-fails.txt", "new content");
      when(checksumService.computeSha256(file)).thenReturn("new-checksum");
      Document existing = existingIndexed(file, "old-checksum", 10L);
      stubParsedInto(file, chunks("chunk1"));
      doThrow(new IllegalStateException("vector store unavailable"))
          .when(vectorStoreWriter)
          .writeEmbeddedChunks(any(), any());

      assertThatThrownBy(() -> service.ingest(localFile(file), null))
          .isInstanceOf(IllegalStateException.class);

      // Twice: once to make room for the new chunks, once to remove whatever the failed write
      // left.
      verify(vectorStore, times(2)).delete(documentIdFilter(existing.getId()));
      verify(documentRepository)
          .markFailedWithoutChunks(
              existing.getId(), FileProcessingService.PROCESSING_FAILED_MESSAGE);
      assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    void anExceptionInTheFinalUpdateRemovesTheWrittenChunks() throws IOException {
      Path file = fileNamed("fails-on-final-update.txt", "content");
      stubNewRow(file, "sha256-of-final-update-failure");
      stubParsedInto(file, chunks("chunk1"));
      when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), anyString(), any()))
          .thenThrow(new RuntimeException("final update blew up"));

      assertThatThrownBy(() -> service.ingest(localFile(file), null))
          .isInstanceOf(RuntimeException.class)
          .hasMessage("final update blew up");

      // storeChunks already ran before the final update failed - the catch block must remove
      // exactly those chunks, keyed by this document's id, or they become orphaned.
      verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
      UUID documentId = savedDocument().getId();
      verify(vectorStore).delete(documentIdFilter(documentId));
      verify(documentRepository)
          .markFailedWithoutChunks(documentId, FileProcessingService.PROCESSING_FAILED_MESSAGE);
    }

    @Test
    void aRowDeletedWhileItsChunksWereWrittenIsSkippedAndTheChunksRemovedAgain()
        throws IOException {
      // Simulated as a zero-rows-updated result from the conditional UPDATE, the same outcome a
      // real concurrent DELETE produces - a plain save would have re-inserted the row as a zombie.
      Path file = fileNamed("deleted-mid-flight.txt", "content that outlives its own row");
      stubNewRow(file, "sha256");
      stubParsedInto(file, chunks("chunk1"));
      when(documentRepository.markIndexedFromSource(any(), anyInt(), any(), anyString(), any()))
          .thenReturn(0);

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
      verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
      UUID documentId = savedDocument().getId();
      verify(vectorStore).delete(documentIdFilter(documentId));
      verify(documentRepository, times(1)).save(any(Document.class));
      verify(documentRepository, never()).markFailed(any(), anyString());
      // The deletion race is counted as skipped, not silently dropped - processed + failed +
      // skipped still sum to the number of documents seen.
      assertThat(counter("skipped")).isEqualTo(1.0);
    }

    @Test
    void aRowDeletedBeforeItCouldBeMarkedFailedIsSkipped() throws IOException {
      Path file = fileNamed("empty-then-gone.txt", "");
      stubNewRow(file, "sha256-of-empty");
      when(documentRepository.markFailedWithoutChunks(any(), any())).thenReturn(0);
      when(documentService.parseDocument(file)).thenReturn(List.of());

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
      // No chunks were ever written on this path - nothing to remove from the vector store.
      verify(vectorStore, never()).delete(any(Filter.Expression.class));
      verify(chunkingService, never()).chunkDocuments(anyString(), any());
    }

    @Test
    void aNamedPipelineTheRegistryDoesNotCarryIsAWiringError() throws IOException {
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), PAGE_URL))
          .thenReturn(Optional.empty());
      DocumentIngest page =
          DocumentIngests.confluencePage(
              targetLibrary, "<p>Text</p>", "Titel", PAGE_URL, "1", null, null);

      assertThatThrownBy(() -> service.ingest(page, null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("confluence");
      verify(documentRepository, never()).save(any(Document.class));
    }
  }

  @Nested
  class RowStates {

    @Test
    void aNewDocumentGetsARowWithLibraryOrganizationDetectedTypeAndChunkBookkeeping()
        throws IOException {
      Path file = fileNamed("library-metadata.txt", "some content");
      stubNewRow(file, "abc123");
      var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
      when(documentService.parseDocument(file)).thenReturn(parsed);
      when(chunkingService.chunkDocuments(eq("library-metadata.txt"), eq(parsed)))
          .thenReturn(
              List.of(
                  new org.springframework.ai.document.Document(
                      "chunk1", Map.of(ChunkingService.LOCATION_METADATA_KEY, "S. 2"))));

      service.ingest(localFile(file), null);

      Document saved = savedDocument();
      assertThat(saved.getLibraryId()).isEqualTo(targetLibrary.getId());
      assertThat(saved.getOrganizationId()).isEqualTo(targetLibrary.getOrganizationId());
      assertThat(saved.getSourceType()).isEqualTo(DocumentSourceType.FILESYSTEM);
      assertThat(saved.getFilePath()).isEqualTo(file.toAbsolutePath().toString());
      // content_type is what the routing's own content detection saw, not a guess from the name.
      assertThat(saved.getContentType()).isEqualTo("text/plain");
      assertThat(saved.getFileSize()).isEqualTo(Files.size(file));

      Map<String, Object> metadata = storedChunks().getFirst().getMetadata();
      assertThat(metadata).containsEntry("library_id", targetLibrary.getId().toString());
      assertThat(metadata)
          .containsEntry("organization_id", targetLibrary.getOrganizationId().toString());
      assertThat(metadata).containsEntry(ChunkingService.LOCATION_METADATA_KEY, "S. 2");
      // ingestion-pipelines.md, Querschnittsregel (d): every chunk names the verfahren that
      // produced it and the routing key actually used.
      assertThat(metadata)
          .containsEntry(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY, TikaFallbackPipeline.ID)
          .containsEntry(
              ChunkPipelineMetadata.PIPELINE_VERSION_METADATA_KEY,
              (int) TikaFallbackPipeline.VERSION)
          .containsEntry(ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY, ".txt");
    }

    @Test
    void anUnchangedIndexedDocumentIsSkippedWithoutParsingOrWriting() throws IOException {
      Path file = fileNamed("unchanged.txt", "same content");
      when(checksumService.computeSha256(file)).thenReturn("matching-checksum");
      existingIndexed(file, "matching-checksum", 0L);

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
      verify(documentService, never()).parseDocument(any());
      verify(chunkingService, never()).chunkDocuments(anyString(), any());
      verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
      verify(vectorStore, never()).delete(any(Filter.Expression.class));
      verify(documentRepository, never()).save(any(Document.class));
      verify(documentRepository, never())
          .markIndexedFromSource(any(), anyInt(), any(), any(), any());
      assertThat(counter("skipped")).isEqualTo(1.0);
    }

    @Test
    void anUnchangedDocumentFoundInAnotherFolderIsMovedThere() throws IOException {
      // The folder is provenance, not content: a moved file keeps its chunks and picks up its new
      // folder with a plain save of the row.
      Path file = fileNamed("moved.txt", "same content");
      when(checksumService.computeSha256(file)).thenReturn("matching-checksum");
      Document existing = existingIndexed(file, "matching-checksum", 0L);
      UUID folderId = UUID.randomUUID();

      FileProcessingResult result =
          service.ingest(
              DocumentIngest.localFile(targetLibrary, file).folder(folderId).build(), null);

      assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
      assertThat(existing.getFolderId()).isEqualTo(folderId);
      verify(documentRepository).save(existing);
      verify(documentService, never()).parseDocument(any());
    }

    @Test
    void anUnchangedDocumentUnderANewMarkerTitleOrPlaceOnlyMovesThose() throws IOException {
      // A title-only or label-only edit bumps the version without changing the body: the chunks
      // stay, only last_modified_remote advances so the next run's pre-fetch check skips the
      // document again, and the new title and place become visible without a chunk changing.
      Document existing =
          new Document("Abschnitt 1.1", PAGE_URL, "text/html", 40L, DocumentSourceType.CONFLUENCE);
      existing.setStatus(DocumentStatus.INDEXED);
      existing.setChecksum("sha256-of-page");
      existing.setChunkCount(2);
      existing.setIndexedAt(Instant.parse("2026-09-01T08:00:00Z"));
      existing.setLastModifiedRemote("7");
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-page");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), PAGE_URL))
          .thenReturn(Optional.of(existing));

      FileProcessingResult result =
          service.ingest(
              DocumentIngests.confluencePage(
                  targetLibrary,
                  "unveränderter Text",
                  "Abschnitt 1.1 (umbenannt)",
                  PAGE_URL,
                  "8",
                  Instant.parse("2026-02-01T08:00:00Z"),
                  new SourceDocumentContext("ENG", null)),
              null);

      assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
      verify(documentRepository)
          .markIndexedFromSource(
              existing.getId(), 2, Instant.parse("2026-09-01T08:00:00Z"), "sha256-of-page", "8");
      verify(documentRepository)
          .refreshConnectorTitleAndContext(
              existing.getId(), "Abschnitt 1.1 (umbenannt)", "ENG", null);
      verify(documentRepository, never()).save(any(Document.class));
      verify(documentRepository, never()).delete(any(Document.class));
    }

    @Test
    void anUnchangedDocumentWhoseProvenanceDidNotChangeWritesNothing() throws IOException {
      // The refresh is conditional: an entry re-seen under the same marker, title and place
      // costs no UPDATE at all.
      Document existing =
          new Document("Titel", ENTRY_URL, "text/html", 10L, DocumentSourceType.RSS_FEED);
      existing.setStatus(DocumentStatus.INDEXED);
      existing.setChecksum("sha256-of-entry");
      existing.setLastModifiedRemote(PUBLISHED_AT);
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), ENTRY_URL))
          .thenReturn(Optional.of(existing));

      FileProcessingResult result =
          service.ingest(
              DocumentIngests.rssEntry(targetLibrary, "text", "Titel", ENTRY_URL, PUBLISHED_AT),
              null);

      assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
      verify(documentRepository, never())
          .markIndexedFromSource(any(), anyInt(), any(), any(), any());
      verify(documentRepository, never())
          .refreshConnectorTitleAndContext(any(), any(), any(), any());
      verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void aChangedDocumentIsUpdatedInPlaceAndItsChunksExchangedAfterParsing() throws IOException {
      // Updated under its own id - never deleted and recreated, so every attachment's
      // parent_document_id stays valid - and the old chunks go only once the new ones are in hand.
      Path file = fileNamed("changed.txt", "new content");
      when(checksumService.computeSha256(file)).thenReturn("new-checksum");
      Document existing = existingIndexed(file, "old-checksum", 10L);
      stubParsedInto(file, chunks("chunk1"));

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(vectorStore).delete(documentIdFilter(existing.getId()));
      verify(documentRepository, never()).delete(any(Document.class));
      Document saved = savedDocument();
      assertThat(saved.getId()).isEqualTo(existing.getId());
      assertThat(saved.getLibraryId()).isEqualTo(targetLibrary.getId());
      assertThat(saved.getOrganizationId()).isEqualTo(targetLibrary.getOrganizationId());
      assertThat(saved.getFileSize()).isEqualTo(Files.size(file));
      verify(documentRepository)
          .markIndexedFromSource(eq(existing.getId()), eq(1), any(), eq("new-checksum"), eq(null));
    }

    @Test
    void aChangedTextDocumentTakesItsNewTitleAndPlaceWithTheRow() throws IOException {
      Document existing =
          new Document("Abschnitt 1.1", PAGE_URL, "text/html", 40L, DocumentSourceType.CONFLUENCE);
      existing.setStatus(DocumentStatus.INDEXED);
      existing.setChecksum("sha256-old");
      existing.setLibraryId(targetLibrary.getId());
      existing.setOrganizationId(targetLibrary.getOrganizationId());
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-new");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), PAGE_URL))
          .thenReturn(Optional.of(existing));
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      String body = "<h1>Neu</h1><p>Text.</p>";

      FileProcessingResult result =
          serviceWith(
                  TestPipelineRegistries.fallbackAndConfluence(documentService, chunkingService))
              .ingest(
                  DocumentIngests.confluencePage(
                      targetLibrary,
                      body,
                      "Abschnitt 1.1 (neu)",
                      PAGE_URL,
                      "9",
                      Instant.parse("2026-02-01T08:00:00Z"),
                      new SourceDocumentContext("ENG", "Handbuch")),
                  null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(documentRepository, never()).delete(any(Document.class));
      verify(vectorStore).delete(documentIdFilter(existing.getId()));
      Document saved = savedDocument();
      assertThat(saved.getId()).isEqualTo(existing.getId());
      assertThat(saved.getFileName()).isEqualTo("Abschnitt 1.1 (neu)");
      assertThat(saved.getSourceHierarchyPath()).isEqualTo("Handbuch");
      assertThat(saved.getFileSize()).isEqualTo((long) body.length());
      verify(documentRepository)
          .markIndexedFromSource(eq(existing.getId()), anyInt(), any(), eq("sha256-new"), eq("9"));
    }

    @Test
    void aDocumentWithoutAChecksumIsReprocessed() throws IOException {
      Path file = fileNamed("legacy.txt", "legacy content");
      when(checksumService.computeSha256(file)).thenReturn("computed-checksum");
      Document existing = existingIndexed(file, null, 10L);
      stubParsedInto(file, chunks("chunk1"));

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(vectorStore).delete(documentIdFilter(existing.getId()));
      verify(documentRepository, never()).delete(any(Document.class));
    }

    @Test
    void aFailedDocumentIsReprocessedEvenWithTheSameChecksum() throws IOException {
      Path file = fileNamed("failed.txt", "failed content");
      when(checksumService.computeSha256(file)).thenReturn("same-checksum");
      Document existing = existingIndexed(file, "same-checksum", 10L);
      existing.setStatus(DocumentStatus.FAILED);
      stubParsedInto(file, chunks("chunk1"));

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(documentService).parseDocument(file);
    }

    @Test
    void theSameIdentityInAnotherLibraryIsAnIndependentDocument() throws IOException {
      // Identity is (library_id, file_path), not file_path alone: another library's document for
      // this exact path is never found here, and this run creates its own instead of touching it.
      Path file = fileNamed("independent.txt", "same path indexed into two libraries");
      KnowledgeLibrary otherLibrary = library();
      Document docInOtherLibrary =
          new Document("independent.txt", file.toAbsolutePath().toString(), null, 10L);
      docInOtherLibrary.setLibraryId(otherLibrary.getId());
      docInOtherLibrary.setChecksum("same-checksum");
      docInOtherLibrary.setStatus(DocumentStatus.INDEXED);
      lenient()
          .when(
              documentRepository.findByLibraryIdAndFilePath(
                  otherLibrary.getId(), file.toAbsolutePath().toString()))
          .thenReturn(Optional.of(docInOtherLibrary));
      stubNewRow(file, "same-checksum");
      stubParsedInto(file, chunks("chunk1"));

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(documentRepository, never()).delete(docInOtherLibrary);
      verify(vectorStore, never()).delete(any(Filter.Expression.class));
      assertThat(savedDocument().getLibraryId()).isEqualTo(targetLibrary.getId());
      assertThat(storedChunks().getFirst().getMetadata())
          .containsEntry("library_id", targetLibrary.getId().toString());
    }

    @Test
    void theSameIdentityFromTwoParentsBecomesOneDocument() throws IOException {
      // The "one document" claim rests on findByLibraryIdAndFilePath - exercised with a stateful
      // repository double whose conditional UPDATE applies the INDEXED transition itself.
      Path fileFromFirstEntry = fileNamed("anlage-erster-lauf.pdf", "geteilter inhalt");
      Path fileFromSecondEntry = fileNamed("anlage-zweiter-lauf.pdf", "geteilter inhalt");
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
      when(chunkingService.chunkDocuments(anyString(), eq(parsed))).thenReturn(chunks("chunk1"));

      FileProcessingResult firstResult =
          service.ingest(
              DocumentIngests.downloadedFile(
                      targetLibrary, fileFromFirstEntry, "anlage.pdf", attachmentUrl, null, 17)
                  .sourceType(DocumentSourceType.RSS_FEED)
                  .sourceEntryUrl("https://example.gov/artikel/erster-artikel")
                  .build(),
              null);
      FileProcessingResult secondResult =
          service.ingest(
              DocumentIngests.downloadedFile(
                      targetLibrary, fileFromSecondEntry, "anlage.pdf", attachmentUrl, null, 17)
                  .sourceType(DocumentSourceType.RSS_FEED)
                  .sourceEntryUrl("https://example.gov/artikel/zweiter-artikel")
                  .build(),
              null);

      assertThat(firstResult).isEqualTo(FileProcessingResult.PROCESSED);
      assertThat(secondResult).isEqualTo(FileProcessingResult.SKIPPED);
      assertThat(savedByFilePath).hasSize(1);
      // The first entry's origin survives - the second call never touched the row again.
      assertThat(savedByFilePath.get(attachmentUrl).getSourceEntryUrl())
          .isEqualTo("https://example.gov/artikel/erster-artikel");
      verify(documentRepository, never()).delete(any());
      verify(vectorStoreWriter, times(1)).writeEmbeddedChunks(any(), any());
    }
  }

  @Nested
  class Quota {

    @Test
    void aNewDocumentOverTheQuotaIsRejectedWithoutPersistingAnything() throws IOException {
      Path file = fileNamed("over-quota.txt", "some content");
      when(checksumService.computeSha256(file)).thenReturn("abc123");
      when(documentRepository.findByLibraryIdAndFilePath(
              targetLibrary.getId(), file.toAbsolutePath().toString()))
          .thenReturn(Optional.empty());
      when(storageQuotaService.wouldExceedQuota(eq(targetLibrary.getId()), anyLong()))
          .thenReturn(true);

      FileProcessingResult result = service.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.QUOTA_EXCEEDED);
      verify(documentRepository, never()).save(any(Document.class));
      verify(documentService, never()).parseDocument(any());
      verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
      assertThat(counter("skipped")).isEqualTo(1.0);
    }

    @Test
    void aChangedDocumentIsMeasuredByItsSizeDeltaNotItsFullNewSize() throws IOException {
      // The row being replaced is never deleted, so usedBytes still includes its old size: with a
      // quota of 1000 and a 900-byte row replaced by 950 bytes, the full new size would see
      // 900 + 950 > 1000 and wrongly reject; the delta sees 900 + 50 <= 1000 and accepts.
      // A real LibraryStorageQuotaService, so the delta is genuinely exercised.
      FileProcessingService serviceWithRealQuota =
          serviceWith(
              TestPipelineRegistries.fallbackOnly(documentService, chunkingService),
              new LibraryStorageQuotaService(documentRepository, new LibraryProperties(1000)));
      Path file = fileNamed("replace-under-quota.txt", "x".repeat(950));
      when(checksumService.computeSha256(file)).thenReturn("new-checksum");
      Document existing = existingIndexed(file, "old-checksum", 900L);
      when(documentRepository.sumFileSizeByLibraryId(targetLibrary.getId())).thenReturn(900L);
      stubParsedInto(file, chunks("chunk1"));

      FileProcessingResult result = serviceWithRealQuota.ingest(localFile(file), null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(documentRepository, never()).delete(any(Document.class));
      assertThat(savedDocument().getId()).isEqualTo(existing.getId());
    }

    @Test
    void aChangedTextDocumentPassesTheDeltaToTheQuotaCheck() throws IOException {
      Document existing =
          new Document("Alter Titel", ENTRY_URL, "text/html", 1_000L, DocumentSourceType.RSS_FEED);
      existing.setLibraryId(targetLibrary.getId());
      existing.setOrganizationId(targetLibrary.getOrganizationId());
      existing.setChecksum("old-sha256");
      existing.setStatus(DocumentStatus.INDEXED);
      String newContent = "neuer Inhalt"; // shorter than the old 1000-byte size
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("new-sha256");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), ENTRY_URL))
          .thenReturn(Optional.of(existing));
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      when(chunkingService.chunkDocuments(anyString(), any())).thenReturn(chunks("neuer Inhalt"));

      service.ingest(
          DocumentIngests.rssEntry(
              targetLibrary, newContent, "Neuer Titel", ENTRY_URL, PUBLISHED_AT),
          null);

      long expectedDelta =
          newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 1_000L;
      verify(storageQuotaService).wouldExceedQuota(targetLibrary.getId(), expectedDelta);
      verify(storageQuotaService, never())
          .wouldExceedQuota(eq(targetLibrary.getId()), longThat(value -> value != expectedDelta));
    }

    @Test
    void aQuotaRejectionLeavesTheExistingRowAndItsChunksExactlyAsTheyWere() throws IOException {
      // Checked before anything is touched: a rejected update never leaves a previously working
      // row INDEXED with a stale checksum and no chunks behind.
      Document existing =
          new Document("Alter Titel", ENTRY_URL, "text/html", 10L, DocumentSourceType.RSS_FEED);
      existing.setLibraryId(targetLibrary.getId());
      existing.setChecksum("old-sha256");
      existing.setStatus(DocumentStatus.INDEXED);
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("new-sha256");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), ENTRY_URL))
          .thenReturn(Optional.of(existing));
      when(storageQuotaService.wouldExceedQuota(eq(targetLibrary.getId()), anyLong()))
          .thenReturn(true);

      FileProcessingResult result =
          service.ingest(
              DocumentIngests.rssEntry(
                  targetLibrary,
                  "neuer, groesserer Inhalt",
                  "Neuer Titel",
                  ENTRY_URL,
                  PUBLISHED_AT),
              null);

      assertThat(result).isEqualTo(FileProcessingResult.QUOTA_EXCEEDED);
      verify(vectorStore, never()).delete(any(Filter.Expression.class));
      verify(documentRepository, never()).save(any(Document.class));
      assertThat(existing.getChecksum()).isEqualTo("old-sha256");
      assertThat(existing.getFileName()).isEqualTo("Alter Titel");
      assertThat(existing.getFileSize()).isEqualTo(10L);
      assertThat(existing.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    }
  }

  @Nested
  class ContentAndProvenance {

    @Test
    void aDownloadedFileKeepsItsOriginalNameIdentityAndChangeMarker() throws IOException {
      Path tempFile = Files.createTempFile(tempDir, "opaa-", ".pdf");
      Files.writeString(tempFile, "pdf content");
      String remoteUrl = "https://example.com/docs/my-report.pdf";
      when(checksumService.computeSha256(tempFile)).thenReturn("sha256-of-pdf");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), remoteUrl))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
      when(documentService.parseDocument(tempFile)).thenReturn(parsed);
      when(chunkingService.chunkDocuments(eq("my-report.pdf"), eq(parsed)))
          .thenReturn(chunks("chunk1"));

      FileProcessingResult result =
          service.ingest(
              DocumentIngests.downloadedFile(
                      targetLibrary, tempFile, "my-report.pdf", remoteUrl, "2025-06-15 10:30", 1024)
                  .build(),
              null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      Document saved = savedDocument();
      assertThat(saved.getFileName()).isEqualTo("my-report.pdf");
      assertThat(saved.getFilePath()).isEqualTo(remoteUrl);
      assertThat(saved.getSourceType()).isEqualTo(DocumentSourceType.HTTP_DIRECTORY);
      // The size the source reported, not the local copy's.
      assertThat(saved.getFileSize()).isEqualTo(1024L);
      verify(documentRepository)
          .markIndexedFromSource(
              eq(saved.getId()), eq(1), any(), eq("sha256-of-pdf"), eq("2025-06-15 10:30"));
    }

    @Test
    void anAttachmentRecordsItsParentOriginAndPlaceInTheSource() throws IOException {
      Path file = fileNamed("notizen.txt", "Notizen");
      String url = "https://wiki.behoerde.example/download/attachments/102/notizen.txt";
      when(checksumService.computeSha256(file)).thenReturn("sha256-notizen");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), url))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      stubParsedInto(file, chunks("Notizen"));
      UUID pageDocumentId = UUID.randomUUID();

      FileProcessingResult result =
          service.ingest(
              DocumentIngests.downloadedFile(targetLibrary, file, "notizen.txt", url, "3", 7L)
                  .sourceType(DocumentSourceType.CONFLUENCE)
                  .sourceEntryUrl(PAGE_URL)
                  .parentDocumentId(pageDocumentId)
                  .context(new SourceDocumentContext("ENG", "Handbuch / Abschnitt 1.1"))
                  .build(),
              null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      Document saved = savedDocument();
      assertThat(saved.getSourceType()).isEqualTo(DocumentSourceType.CONFLUENCE);
      assertThat(saved.getParentDocumentId()).isEqualTo(pageDocumentId);
      assertThat(saved.getSourceEntryUrl()).isEqualTo(PAGE_URL);
      assertThat(saved.getSourceContainerKey()).isEqualTo("ENG");
      assertThat(saved.getSourceHierarchyPath()).isEqualTo("Handbuch / Abschnitt 1.1");
      verify(documentRepository)
          .markIndexedFromSource(eq(saved.getId()), eq(1), any(), eq("sha256-notizen"), eq("3"));
    }

    @Test
    void textThatNeverWasAFileGoesToTheFallbackPipelineAsHtmlNamedByItsTitle() throws IOException {
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), ENTRY_URL))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      when(chunkingService.chunkDocuments(eq("Titel"), any())).thenReturn(chunks("chunk1"));

      FileProcessingResult result =
          service.ingest(
              DocumentIngests.rssEntry(
                  targetLibrary, "entry main text", "Titel", ENTRY_URL, PUBLISHED_AT),
              null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(documentService, never()).parseDocument(any());
      Document saved = savedDocument();
      assertThat(saved.getFileName()).isEqualTo("Titel");
      assertThat(saved.getFilePath()).isEqualTo(ENTRY_URL);
      assertThat(saved.getContentType()).isEqualTo("text/html");
      assertThat(saved.getSourceType()).isEqualTo(DocumentSourceType.RSS_FEED);
      assertThat(saved.getFileSize()).isEqualTo((long) "entry main text".length());
      // No routing decision was ever made for text, so no routing key is written.
      assertThat(storedChunks().getFirst().getMetadata())
          .doesNotContainKey(ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY)
          .containsEntry("file_name", "Titel");
      verify(documentRepository)
          .markIndexedFromSource(
              eq(saved.getId()), eq(1), any(), eq("sha256-of-entry"), eq(PUBLISHED_AT));
    }

    @Test
    void theDeclaredPropertiesReachTheCoreFieldExtraction() throws IOException {
      // The headline, the synthetic name and the publication instant are the source's own
      // declarations (ADR-0024) - laid over what the pipeline found, before the extraction runs.
      io.opaa.indexing.metadata.DocumentMetadataService metadataService =
          Mockito.mock(io.opaa.indexing.metadata.DocumentMetadataService.class);
      when(metadataService.applyDeterministicExtraction(any(), any(), any()))
          .thenReturn(io.opaa.indexing.metadata.DocumentChunkMetadata.EMPTY);
      FileProcessingService probing =
          new FileProcessingService(
              TestPipelineRegistries.fallbackOnly(documentService, chunkingService),
              documentRepository,
              vectorChunkStore,
              checksumService,
              new IndexingMetrics(meterRegistry),
              storageQuotaService,
              defaultIndexingProperties(),
              Runnable::run,
              Mockito.mock(ObjectProvider.class),
              new AttachmentDownloadLimits(0, 0, 0, ""),
              metadataService);
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), ENTRY_URL))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      when(chunkingService.chunkDocuments(anyString(), any())).thenReturn(chunks("chunk1"));

      probing.ingest(
          DocumentIngests.rssEntry(
              targetLibrary, "entry main text", "Titel", ENTRY_URL, PUBLISHED_AT),
          null);

      ArgumentCaptor<DocumentProperties> properties =
          ArgumentCaptor.forClass(DocumentProperties.class);
      verify(metadataService)
          .applyDeterministicExtraction(any(), eq("Titel"), properties.capture());
      assertThat(properties.getValue().title()).isEqualTo("Titel");
      assertThat(properties.getValue().syntheticName()).isTrue();
      assertThat(properties.getValue().documentDate()).isEqualTo(LocalDate.of(2025, 6, 15));
    }

    @Test
    void aHeadlineIsTheEmbedPrefixVerbatimEvenWithAnInteriorPeriod() throws IOException {
      // A headline is free text, not a filesystem-style "NNN_slug.ext" name - running it through
      // ChunkContextTitle would truncate "...zum 1. Januar" at the sentence-internal period.
      String headline = "Neue Regelung tritt zum 1. Januar in Kraft";
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), ENTRY_URL))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      when(chunkingService.chunkDocuments(eq(headline), any()))
          .thenReturn(chunks("first chunk text", "second chunk text"));

      service.ingest(
          DocumentIngests.rssEntry(
              targetLibrary, "entry main text", headline, ENTRY_URL, PUBLISHED_AT),
          null);

      assertThat(storedChunks().getFirst().getFormattedContent(MetadataMode.EMBED))
          .isEqualTo("[" + headline + "]\n\nfirst chunk text");
    }

    @Test
    void textWithoutATitleGetsNoEmbedPrefixAtAll() throws IOException {
      // Without a title the name falls back to the URL - every entry of one feed would share a
      // domain/path prefix, the boilerplate pattern the eval measurement found harmful.
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), ENTRY_URL))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      when(chunkingService.chunkDocuments(eq(ENTRY_URL), any()))
          .thenReturn(chunks("first chunk text", "second chunk text"));

      service.ingest(
          DocumentIngests.rssEntry(targetLibrary, "entry main text", null, ENTRY_URL, PUBLISHED_AT),
          null);

      assertThat(savedDocument().getFileName()).isEqualTo(ENTRY_URL);
      assertThat(storedChunks().getFirst().getFormattedContent(MetadataMode.EMBED))
          .isEqualTo("first chunk text");
    }

    @Test
    void aFileNamedAttachmentOfATextSourceGetsAHumanizedTitleNotItsRawName() throws IOException {
      // An RSS attachment carries RSS_FEED as its source type too, but its name is a real file
      // name - the prefix rule follows the name's kind, never the source type.
      Path file = fileNamed("001_satzung.pdf", "pdf content");
      String url = "https://example.gov/downloads/001_satzung.pdf";
      when(checksumService.computeSha256(file)).thenReturn("sha256-of-attachment");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), url))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      stubParsedInto(file, chunks("first chunk text", "second chunk text"));

      service.ingest(
          DocumentIngests.downloadedFile(targetLibrary, file, "001_satzung.pdf", url, null, 1024)
              .sourceType(DocumentSourceType.RSS_FEED)
              .sourceEntryUrl("https://example.gov/artikel/mein-artikel")
              .build(),
          null);

      Document saved = savedDocument();
      assertThat(saved.getSourceType()).isEqualTo(DocumentSourceType.RSS_FEED);
      assertThat(saved.getSourceEntryUrl()).isEqualTo("https://example.gov/artikel/mein-artikel");
      assertThat(storedChunks().getFirst().getFormattedContent(MetadataMode.EMBED))
          .isEqualTo("[satzung]\n\nfirst chunk text");
    }

    @Test
    void aNamedPipelineRunsWithThePlaceInTheSourceAsPrefixAndOnEveryChunk() throws IOException {
      // ADR-0023: identity by the title-free page URL, the version as change marker, space and
      // ancestors in the context columns and on every chunk, the outline as the embed prefix.
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-page");
      when(documentRepository.findByLibraryIdAndFilePath(targetLibrary.getId(), PAGE_URL))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

      // two h1 sections -> two chunks through the real Confluence pipeline (no mocked chunking)
      FileProcessingResult result =
          serviceWith(
                  TestPipelineRegistries.fallbackAndConfluence(documentService, chunkingService))
              .ingest(
                  DocumentIngests.confluencePage(
                      targetLibrary,
                      "<h1>Zuständigkeiten</h1><p>Das Bauamt bearbeitet Anträge.</p>"
                          + "<h1>Fristen</h1><p>14 Tage.</p>",
                      "Abschnitt 1.1",
                      PAGE_URL,
                      "7",
                      Instant.parse("2026-02-01T08:00:00Z"),
                      new SourceDocumentContext("ENG", "Handbuch / Kapitel 1")),
                  null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      Document saved = savedDocument();
      assertThat(saved.getSourceType()).isEqualTo(DocumentSourceType.CONFLUENCE);
      assertThat(saved.getFileName()).isEqualTo("Abschnitt 1.1");
      assertThat(saved.getFilePath()).isEqualTo(PAGE_URL);
      assertThat(saved.getSourceContainerKey()).isEqualTo("ENG");
      assertThat(saved.getSourceHierarchyPath()).isEqualTo("Handbuch / Kapitel 1");
      verify(documentRepository)
          .markIndexedFromSource(eq(saved.getId()), eq(2), any(), eq("sha256-of-page"), eq("7"));
      org.springframework.ai.document.Document firstChunk = storedChunks().getFirst();
      assertThat(firstChunk.getFormattedContent(MetadataMode.EMBED))
          .isEqualTo(
              "[Handbuch / Kapitel 1 / Abschnitt 1.1]\n\nZuständigkeiten\n\nDas Bauamt bearbeitet"
                  + " Anträge.");
      assertThat(firstChunk.getMetadata())
          .containsEntry("source_container_key", "ENG")
          .containsEntry("source_hierarchy_path", "Handbuch / Kapitel 1")
          .containsEntry("file_name", "Abschnitt 1.1")
          .containsEntry("pipeline_id", "confluence");
    }

    @Test
    void withoutAnAttachmentAccessDiscoveredAttachmentsAreDiscardedAndTheParentKeepsItsSize()
        throws IOException {
      // Reducing the parent's size without indexing the attachments would under-count the quota.
      Path attachmentTempFile = fileNamed("discovered-attachment.tmp", "attachment bytes");
      var attachment =
          new DiscoveredAttachment("anlage.pdf", attachmentTempFile, "application/pdf");
      var fakePipeline =
          new FakeDiscoveringPipeline(chunks("chunk1"), List.of(attachment), Optional.of(3L));
      FileProcessingService serviceWithFakePipeline =
          serviceWith(new DocumentPipelineRegistry(List.of(fakePipeline), fakePipeline));
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
      when(documentRepository.findByLibraryIdAndFilePath(eq(targetLibrary.getId()), anyString()))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

      serviceWithFakePipeline.ingest(
          DocumentIngests.rssEntry(
              targetLibrary, "entry main text", "Titel", ENTRY_URL, PUBLISHED_AT),
          null);

      // One save only - the size override never ran - and the runner still removed the temp file.
      verify(documentRepository, times(1)).save(any(Document.class));
      assertThat(savedDocument().getFileSize()).isEqualTo((long) "entry main text".length());
      assertThat(Files.exists(attachmentTempFile)).isFalse();
    }

    @Test
    void withAnAttachmentAccessTheParentSizeIsCorrectedBeforeTheAttachmentsAreIndexed()
        throws IOException {
      Path attachmentTempFile = fileNamed("discovered-attachment.tmp", "attachment bytes");
      var attachment =
          new DiscoveredAttachment("anlage.pdf", attachmentTempFile, "application/pdf");
      var fakePipeline =
          new FakeDiscoveringPipeline(chunks("chunk1"), List.of(attachment), Optional.of(3L));
      AttachmentIndexer attachmentIndexer = Mockito.mock(AttachmentIndexer.class);
      @SuppressWarnings("unchecked")
      ObjectProvider<AttachmentIndexer> provider = Mockito.mock(ObjectProvider.class);
      when(provider.getObject()).thenReturn(attachmentIndexer);
      FileProcessingService serviceWithFakePipeline =
          serviceWith(
              new DocumentPipelineRegistry(List.of(fakePipeline), fakePipeline),
              storageQuotaService,
              provider);
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
      when(documentRepository.findByLibraryIdAndFilePath(eq(targetLibrary.getId()), anyString()))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
      AttachmentAccess access = Mockito.mock(AttachmentAccess.class);

      serviceWithFakePipeline.ingest(
          DocumentIngests.rssEntry(
              targetLibrary, "entry main text", "Titel", ENTRY_URL, PUBLISHED_AT),
          access);

      // The row's own save, then the override's - before the attachment path sees the parent.
      ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
      verify(documentRepository, times(2)).save(saved.capture());
      Document parent = saved.getAllValues().getLast();
      assertThat(parent.getFileSize()).isEqualTo(3L);
      @SuppressWarnings("unchecked")
      ArgumentCaptor<List<AttachmentSource>> sources = ArgumentCaptor.forClass(List.class);
      verify(attachmentIndexer)
          .indexAll(
              eq(access),
              sources.capture(),
              eq(parent.getId()),
              eq(ENTRY_URL),
              eq(DocumentSourceType.RSS_FEED),
              any());
      AttachmentSource.LocalFile source =
          (AttachmentSource.LocalFile) sources.getValue().getFirst();
      assertThat(source.fileName()).isEqualTo("anlage.pdf");
      assertThat(source.filePathIdentity())
          .isEqualTo(AttachmentFilePath.of(ENTRY_URL, 0, "anlage.pdf"));
    }
  }

  @Nested
  class StoredRows {

    private Document pendingUpload(String fileName) {
      Document doc =
          new Document(
              fileName,
              tempDir.resolve(fileName).toString(),
              "application/pdf",
              5L,
              DocumentSourceType.UPLOAD);
      doc.setLibraryId(targetLibrary.getId());
      doc.setOrganizationId(targetLibrary.getOrganizationId());
      doc.setUploadedByUserId(UUID.randomUUID());
      doc.setChecksum("checksum-" + fileName);
      lenient()
          .when(
              documentRepository.findByLibraryIdAndFilePath(
                  targetLibrary.getId(), doc.getFilePath()))
          .thenReturn(Optional.of(doc));
      return doc;
    }

    private DocumentIngest upload(Document doc, Path file) throws IOException {
      return DocumentIngest.builder(targetLibrary)
          .file(file)
          .filePath(doc.getFilePath())
          .fileName(doc.getFileName())
          .sourceType(DocumentSourceType.UPLOAD)
          .existingRow()
          .build();
    }

    private DocumentIngest reindex(Document doc, Path file) {
      return DocumentIngest.builder(targetLibrary)
          .file(file, doc.getFileSize())
          .filePath(doc.getFilePath())
          .fileName(doc.getFileName())
          .sourceType(doc.getSourceType())
          .changeMarker(doc.getLastModifiedRemote())
          .reindex()
          .build();
    }

    @Test
    void anExistingRowIsProcessedWithoutQuotaCheckOrFieldChanges() throws IOException {
      // The row was admitted when it was created: no second quota check, no save, its fields stay,
      // and the successful transition is the same conditional UPDATE every other path uses.
      Path file = fileNamed("upload.pdf", "uploaded pdf content");
      Document doc = pendingUpload("upload.pdf");
      when(checksumService.computeSha256(file)).thenReturn("checksum-upload.pdf");
      stubParsedInto(file, chunks("chunk1"));

      FileProcessingResult result = service.ingest(upload(doc, file), null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(storageQuotaService, never()).wouldExceedQuota(any(), anyLong());
      verify(documentRepository, never()).save(any(Document.class));
      assertThat(doc.getContentType()).isEqualTo("application/pdf");
      verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
      verify(documentRepository)
          .markIndexedFromSource(
              eq(doc.getId()), eq(1), any(), eq("checksum-upload.pdf"), eq(null));
    }

    @Test
    void aMissingExistingRowIsSkippedWithoutParsing() throws IOException {
      // The row can be deleted between the synchronous PENDING save and the asynchronous run.
      Path file = fileNamed("deleted-before-processing.pdf", "content");
      when(checksumService.computeSha256(file)).thenReturn("sha256");
      when(documentRepository.findByLibraryIdAndFilePath(eq(targetLibrary.getId()), anyString()))
          .thenReturn(Optional.empty());

      FileProcessingResult result =
          service.ingest(
              DocumentIngest.builder(targetLibrary)
                  .file(file)
                  .filePath(file.toString())
                  .fileName("deleted-before-processing.pdf")
                  .sourceType(DocumentSourceType.UPLOAD)
                  .existingRow()
                  .build(),
              null);

      assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
      verify(documentService, never()).parseDocument(any());
      verify(documentRepository, never()).save(any(Document.class));
      verify(documentRepository, never()).markFailed(any(), anyString());
    }

    @Test
    void anUploadThatFailsAfterItsChunksWereWrittenEndsFailedWithoutThrowing() throws IOException {
      // The asynchronous entry has no caller to rethrow to: the failure is logged, the written
      // chunks are removed and the row ends FAILED with the user-facing reason.
      Path file = fileNamed("fails-on-final-update.pdf", "content");
      Document doc = pendingUpload("fails-on-final-update.pdf");
      when(checksumService.computeSha256(file)).thenReturn("checksum");
      stubParsedInto(file, chunks("chunk1"));
      when(documentRepository.markIndexedFromSource(eq(doc.getId()), eq(1), any(), any(), any()))
          .thenThrow(new RuntimeException("final update blew up"));

      service.processUploadedFileAsync(upload(doc, file), null);

      verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
      // Once to make room before the write (an existing row is always replaced), once to remove
      // what the failed run left behind.
      verify(vectorStore, times(2)).delete(documentIdFilter(doc.getId()));
      verify(documentRepository)
          .markFailedWithoutChunks(doc.getId(), FileProcessingService.PROCESSING_FAILED_MESSAGE);
      verify(documentRepository, never()).delete(any(Document.class));
      assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    void aReindexRunsOverUnchangedContentInsteadOfSkippingIt() throws IOException {
      // A re-index exists to rewrite chunks whose content did not change - the checksum match
      // that skips a connector run must not skip it.
      Path file = fileNamed("unchanged.pdf", "same content");
      Document doc = pendingUpload("unchanged.pdf");
      doc.setStatus(DocumentStatus.INDEXED);
      doc.setChunkCount(1);
      doc.setChecksum("same-checksum");
      when(checksumService.computeSha256(file)).thenReturn("same-checksum");
      stubParsedInto(file, chunks("chunk1"));

      FileProcessingResult result = service.ingest(reindex(doc, file), null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      verify(vectorStore).delete(documentIdFilter(doc.getId()));
      verify(vectorStoreWriter).writeEmbeddedChunks(any(), any());
      verify(documentRepository)
          .markIndexedFromSource(eq(doc.getId()), eq(1), any(), eq("same-checksum"), eq(null));
    }

    @Test
    void aReindexThatCannotParseLeavesTheDocumentsChunksAndStatusUntouched() throws IOException {
      // The likeliest transient failure on the re-index path, before anything was deleted:
      // destroying a functioning document over a failure that may not repeat would be wrong.
      Path file = fileNamed("beschaedigt.pdf", "%PDF-1.4\n%mock-pdf-body-for-magic-byte-detection");
      Document doc = pendingUpload("beschaedigt.pdf");
      doc.setStatus(DocumentStatus.INDEXED);
      when(checksumService.computeSha256(file)).thenReturn("checksum");
      when(documentService.parseDocument(file))
          .thenThrow(new RuntimeException("Tika konnte die Datei nicht lesen"));

      FileProcessingResult result = service.ingest(reindex(doc, file), null);

      assertThat(result).isEqualTo(FileProcessingResult.FAILED);
      verify(vectorStore, never()).delete(any(Filter.Expression.class));
      verify(fullTextChunkStore, never()).deleteByDocumentId(any());
      verify(documentRepository, never()).markFailed(any(), any());
      verify(documentRepository, never()).markFailedWithoutChunks(any(), any());
      verify(documentRepository, never())
          .markIndexedFromSource(any(), anyInt(), any(), any(), any());
    }

    @Test
    void aReindexWhoseWriteThrowsBeforeTheDeleteLeavesTheDocumentUntouched() throws IOException {
      Path file = fileNamed("extraction-throws.pdf", "content");
      Document doc = pendingUpload("extraction-throws.pdf");
      doc.setStatus(DocumentStatus.INDEXED);
      when(checksumService.computeSha256(file)).thenReturn("checksum");
      var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
      when(documentService.parseDocument(file)).thenReturn(parsed);
      when(chunkingService.chunkDocuments(eq("extraction-throws.pdf"), eq(parsed)))
          .thenReturn(chunks("chunk1"));
      // The delete itself fails (a store outage) - the very boundary at which the previous state
      // stops being preserved has not been crossed, so nothing is marked.
      doThrow(new IllegalStateException("vector store down"))
          .when(vectorStore)
          .delete(any(Filter.Expression.class));

      assertThatThrownBy(() -> service.ingest(reindex(doc, file), null))
          .isInstanceOf(IllegalStateException.class);

      verify(vectorStoreWriter, never()).writeEmbeddedChunks(any(), any());
      verify(documentRepository, never()).markFailed(any(), any());
      verify(documentRepository, never()).markFailedWithoutChunks(any(), any());
      assertThat(counter("failed")).isEqualTo(1.0);
    }

    @Test
    void aReindexFailingAfterTheDeleteCleansUpLikeAnyOtherFailure() throws IOException {
      // Past the delete there is no working previous state left: the new chunks go and the row
      // says so, so search never returns orphaned chunks for a FAILED document.
      Path file = fileNamed("write-fails.pdf", "content");
      Document doc = pendingUpload("write-fails.pdf");
      doc.setStatus(DocumentStatus.INDEXED);
      when(checksumService.computeSha256(file)).thenReturn("checksum");
      stubParsedInto(file, chunks("chunk1"));
      doThrow(new IllegalStateException("vector store unavailable"))
          .when(vectorStoreWriter)
          .writeEmbeddedChunks(any(), any());

      assertThatThrownBy(() -> service.ingest(reindex(doc, file), null))
          .isInstanceOf(IllegalStateException.class);

      verify(vectorStore, times(2)).delete(documentIdFilter(doc.getId()));
      verify(documentRepository)
          .markFailedWithoutChunks(doc.getId(), FileProcessingService.PROCESSING_FAILED_MESSAGE);
    }

    @Test
    void aDocumentThatCannotBeReadForFormatDetectionWritesNoRoutingKeyAtAll() throws IOException {
      // A transient read failure during routing (a virus scanner briefly locking the file) must
      // not be persisted as a routing verdict - PipelineReindexService would otherwise treat it as
      // "confirmed fallback" forever. The checksum is mocked, so the file can simply be absent.
      Path file = tempDir.resolve("bericht.pdf");
      Document doc = pendingUpload("bericht.pdf");
      doc.setStatus(DocumentStatus.INDEXED);
      when(checksumService.computeSha256(file)).thenReturn("checksum");
      stubParsedInto(file, chunks("chunk1"));

      FileProcessingResult result = service.ingest(reindex(doc, file), null);

      assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
      assertThat(storedChunks().getFirst().getMetadata())
          .doesNotContainKey(ChunkPipelineMetadata.ROUTING_EXTENSION_METADATA_KEY);
    }
  }

  @Nested
  class ChunkStorage {

    @Test
    void pipelineDeclaredPassthroughKeysRideAlongOnlyWhenThePipelineSetThem() throws IOException {
      // storeChunks reads the registry's declared passthrough keys: a declared-but-absent key is
      // skipped, and an undeclared key present on the chunk is never copied.
      var chunks =
          List.of(
              new org.springframework.ai.document.Document(
                  "chunk1",
                  Map.of(
                      "structural_key", "Kapitel 3",
                      "undeclared_key", "must not ride along")));
      var fakePipeline =
          new FakePassthroughPipeline(Set.of("structural_key", "declared_but_absent_key"), chunks);
      FileProcessingService serviceWithFakePipeline =
          serviceWith(new DocumentPipelineRegistry(List.of(fakePipeline), fakePipeline));
      stubTextRow();

      serviceWithFakePipeline.ingest(
          DocumentIngests.rssEntry(
              targetLibrary, "entry main text", "Titel", ENTRY_URL, PUBLISHED_AT),
          null);

      Map<String, Object> metadata = storedChunks().getFirst().getMetadata();
      assertThat(metadata).containsEntry("structural_key", "Kapitel 3");
      assertThat(metadata).doesNotContainKeys("declared_but_absent_key", "undeclared_key");
    }

    @Test
    void aPipelineCannotOverrideStoreChunksOwnBookkeepingKeysByDeclaringThem() throws IOException {
      // library_id carries the permission-scoped search filter - a chunk that smuggled a different
      // value through here would leak or hide content across library boundaries.
      var chunks =
          List.of(
              new org.springframework.ai.document.Document(
                  "chunk1",
                  Map.of(
                      "file_name",
                      "smuggled-name.txt",
                      "library_id",
                      UUID.randomUUID().toString())));
      var fakePipeline = new FakePassthroughPipeline(Set.of("file_name", "library_id"), chunks);
      FileProcessingService serviceWithFakePipeline =
          serviceWith(new DocumentPipelineRegistry(List.of(fakePipeline), fakePipeline));
      stubTextRow();

      serviceWithFakePipeline.ingest(
          DocumentIngests.rssEntry(
              targetLibrary, "entry main text", "Titel", ENTRY_URL, PUBLISHED_AT),
          null);

      Map<String, Object> metadata = storedChunks().getFirst().getMetadata();
      assertThat(metadata).containsEntry("file_name", "Titel");
      assertThat(metadata).containsEntry("library_id", targetLibrary.getId().toString());
    }

    @Test
    void aSingleChunkDocumentEmbedsByteIdenticalToItsText() throws IOException {
      // A document ChunkingService left as a single chunk gets NO contextual-title prefix at all:
      // what the embedding call sends (MetadataMode.EMBED) is byte-identical to the chunk text.
      Path file = fileNamed("embed-content.txt", "some content");
      stubNewRow(file, "abc123");
      stubParsedInto(file, chunks("the real chunk text to embed"));

      service.ingest(localFile(file), null);

      org.springframework.ai.document.Document storedChunk = storedChunks().getFirst();
      assertThat(storedChunk.getMetadata()).containsKey("library_id");
      assertThat(storedChunk.getText()).isEqualTo("the real chunk text to embed");
      assertThat(storedChunk.getFormattedContent(MetadataMode.EMBED))
          .isEqualTo("the real chunk text to embed");
    }

    @Test
    void aMultiChunkDocumentEmbedsWithAHumanizedContextTitlePrefix() throws IOException {
      // Split into 2 or more chunks, every chunk's embedding input is prefixed with the humanized
      // file name ("001_embed-content.txt" -> "embed content"); the stored text stays unprefixed.
      Path file = fileNamed("001_embed-content.txt", "some content");
      stubNewRow(file, "abc123");
      stubParsedInto(file, chunks("first chunk text", "second chunk text"));

      service.ingest(localFile(file), null);

      List<org.springframework.ai.document.Document> stored = storedChunks();
      assertThat(stored.get(0).getText()).isEqualTo("first chunk text");
      assertThat(stored.get(1).getText()).isEqualTo("second chunk text");
      assertThat(stored.get(0).getFormattedContent(MetadataMode.EMBED))
          .isEqualTo("[embed content]\n\nfirst chunk text");
      assertThat(stored.get(1).getFormattedContent(MetadataMode.EMBED))
          .isEqualTo("[embed content]\n\nsecond chunk text");
    }

    @Test
    void anUnknownMetadataKeyNeverReachesTheEmbeddingCall() throws IOException {
      // The per-chunk formatters never read Document#getMetadata() at all - proven by mutating
      // the metadata of the already-formatter-attached stored chunk and asserting the added key
      // still never surfaces in getFormattedContent(EMBED).
      Path file = fileNamed("001_embed-content.txt", "some content");
      stubNewRow(file, "abc123");
      stubParsedInto(file, chunks("first chunk text", "second chunk text"));

      service.ingest(localFile(file), null);

      org.springframework.ai.document.Document storedChunk = storedChunks().getFirst();
      storedChunk.getMetadata().put("future_bookkeeping_key", "some-future-uuid");
      assertThat(storedChunk.getFormattedContent(MetadataMode.EMBED))
          .isEqualTo("[embed content]\n\nfirst chunk text")
          .doesNotContain("future_bookkeeping_key", "some-future-uuid");
    }

    @Test
    void aDiscoveredAttachmentsTempFileIsDeletedAfterProcessing() throws IOException {
      // ADR-0022, Teil 2: DocumentPipeline#run goes through DocumentPipelineRunner, which owns
      // deleting a reported attachment's temp file.
      Path attachmentTempFile = fileNamed("discovered-attachment.tmp", "attachment bytes");
      var attachment =
          new DiscoveredAttachment("anlage.pdf", attachmentTempFile, "application/pdf");
      var fakePipeline =
          new FakeDiscoveringPipeline(chunks("chunk1"), List.of(attachment), Optional.empty());
      FileProcessingService serviceWithFakePipeline =
          serviceWith(new DocumentPipelineRegistry(List.of(fakePipeline), fakePipeline));
      stubTextRow();

      serviceWithFakePipeline.ingest(
          DocumentIngests.rssEntry(
              targetLibrary, "entry main text", "Titel", ENTRY_URL, PUBLISHED_AT),
          null);

      assertThat(Files.exists(attachmentTempFile)).isFalse();
    }

    private void stubTextRow() {
      when(checksumService.computeSha256(any(byte[].class))).thenReturn("sha256-of-entry");
      when(documentRepository.findByLibraryIdAndFilePath(eq(targetLibrary.getId()), anyString()))
          .thenReturn(Optional.empty());
      when(documentRepository.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    }
  }

  /**
   * A stand-in pipeline declaring an arbitrary passthrough key - stands in for e.g.
   * MailDocumentPipeline's mail_* keys without pulling that pipeline's own parsing into this
   * service-level test. {@code run} simply returns {@code chunksToReturn}; reached as the
   * registry's fallback pipeline, which text content goes to directly.
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

  /**
   * A stand-in pipeline reporting a {@link DiscoveredAttachment} (ADR-0022, Teil 2) and,
   * optionally, a content byte size override for its parent.
   */
  private record FakeDiscoveringPipeline(
      List<org.springframework.ai.document.Document> chunksToReturn,
      List<DiscoveredAttachment> discoveredAttachments,
      Optional<Long> contentByteSizeOverride)
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
      return contentByteSizeOverride
          .map(size -> DocumentPipelineResult.chunked(chunksToReturn, discoveredAttachments, size))
          .orElseGet(() -> DocumentPipelineResult.chunked(chunksToReturn, discoveredAttachments));
    }
  }
}
