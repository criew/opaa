package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.LibraryStorageQuotaService;
import io.opaa.library.LibraryVisibility;
import io.opaa.library.UploadProperties;
import io.opaa.observability.IndexingMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    vectorChunkStore = new VectorChunkStore(vectorStore);
    service =
        new FileProcessingService(
            documentService,
            chunkingService,
            documentRepository,
            vectorStore,
            vectorChunkStore,
            checksumService,
            new IndexingMetrics(meterRegistry),
            storageQuotaService,
            defaultIndexingProperties(),
            Runnable::run);
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
  }

  private KnowledgeLibrary library() {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(), "Bibliothek", null, UUID.randomUUID(), LibraryVisibility.PRIVATE, false);
  }

  // embeddingConcurrency=1: every test in this class exercises the pre-#734 sequential
  // storeChunks path (a single vectorStore.add call) unless it opts into concurrency itself (see
  // EmbeddingConcurrencyTest) - Runnable::run above is therefore never actually invoked here.
  private static IndexingProperties defaultIndexingProperties() {
    return new IndexingProperties(null, 1000, 0, 50, null, null, null, null, null, 1);
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
    verify(vectorStore).add(any());

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
    verify(vectorStore, never()).add(any());
  }

  @Test
  void quotaCheckMeasuresTheDeltaOnlyAfterAnExistingDocumentHasBeenDeleted() throws IOException {
    // #119, PR #700 review finding 5: uses a REAL LibraryStorageQuotaService, not a mock, so the
    // "checked after the old row is deleted, measures the true delta" promise is genuinely
    // exercised rather than merely asserted against a stub. documentRepository stays a mock (a
    // data-layer boundary, not the thing under test) - its sumFileSizeByLibraryId answer flips
    // from the pre-delete to the post-delete figure the moment documentRepository.delete is
    // called, exactly mirroring how the real aggregate query would behave once that DELETE has
    // actually run.
    //
    // Quota 1000, an existing 900-byte document being replaced by a 950-byte one: checking BEFORE
    // the delete would see 900 (old) + 950 (new) = 1850 > 1000 and wrongly reject; checking AFTER
    // (the actual, correct order) sees 0 (old already gone) + 950 = 950 <= 1000 and accepts. A
    // regression that reordered the two calls would flip this test's result from PROCESSED to
    // QUOTA_EXCEEDED.
    LibraryStorageQuotaService realQuotaService =
        new LibraryStorageQuotaService(
            documentRepository, new UploadProperties(null, 0, null, 0, 1000));
    FileProcessingService serviceWithRealQuota =
        new FileProcessingService(
            documentService,
            chunkingService,
            documentRepository,
            vectorStore,
            vectorChunkStore,
            checksumService,
            new IndexingMetrics(meterRegistry),
            realQuotaService,
            defaultIndexingProperties(),
            Runnable::run);

    Path file = tempDir.resolve("replace-under-quota.txt");
    String newContent = "x".repeat(950);
    Files.writeString(file, newContent);

    Document existingDoc =
        new Document(
            "replace-under-quota.txt", file.toAbsolutePath().toString(), "text/plain", 900L);
    existingDoc.setLibraryId(targetLibrary.getId());
    existingDoc.setChecksum("old-checksum");
    existingDoc.setStatus(DocumentStatus.INDEXED);

    AtomicBoolean oldRowDeleted = new AtomicBoolean(false);
    when(checksumService.computeSha256(file)).thenReturn("new-checksum");
    when(documentRepository.findByLibraryIdAndFilePath(
            targetLibrary.getId(), file.toAbsolutePath().toString()))
        .thenReturn(Optional.of(existingDoc));
    doAnswer(
            inv -> {
              oldRowDeleted.set(true);
              return null;
            })
        .when(documentRepository)
        .delete(existingDoc);
    when(documentRepository.sumFileSizeByLibraryId(targetLibrary.getId()))
        .thenAnswer(inv -> oldRowDeleted.get() ? 0L : 900L);
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
    verify(documentRepository).delete(existingDoc);
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
    verify(vectorStore).add(chunkCaptor.capture());
    org.springframework.ai.document.Document storedChunk = chunkCaptor.getValue().getFirst();
    Map<String, Object> metadata = storedChunk.getMetadata();
    assertThat(metadata).containsEntry("library_id", targetLibrary.getId().toString());
    assertThat(metadata)
        .containsEntry("organization_id", targetLibrary.getOrganizationId().toString());
    // #667: the chunk's Fundort rides along to the vector store.
    assertThat(metadata).containsEntry(ChunkingService.LOCATION_METADATA_KEY, "S. 2");
  }

  @Test
  void chunkMetadataIsCarriedForFilteringButExcludedFromWhatGetsEmbedded() throws IOException {
    // Issue #773: EmbeddingModel#getEmbeddingContent(Document) - what actually gets sent to the
    // embedding call for every document VectorStore#add batches - defaults to
    // Document#getFormattedContent(MetadataMode), and org.springframework.ai.openai.
    // OpenAiEmbeddingModel (the only embedding path since #762) defaults its own metadataMode to
    // MetadataMode.EMBED. Without CHUNK_EMBED_CONTENT_FORMATTER excluding this chunk's five
    // bookkeeping keys, MetadataMode.EMBED would prepend all of them - two random UUIDs, an
    // index, a filename, a second random UUID - ahead of the real chunk text, degrading retrieval
    // quality (see FileProcessingService#CHUNK_EMBED_CONTENT_FORMATTER's own Javadoc for the
    // measured effect: cosine similarity between a query and its correct document dropped from
    // 0.698 to 0.357 with this contamination). The metadata itself must still reach the vector
    // store row - the permission-aware query filter (#202) and citations depend on it - so this
    // is about what MetadataMode.EMBED formats into embeddable text, not about removing the
    // metadata map itself (covered by the test directly above).
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
    verify(vectorStore).add(chunkCaptor.capture());
    org.springframework.ai.document.Document storedChunk = chunkCaptor.getValue().getFirst();

    // The metadata is still there for filtering/citation...
    assertThat(storedChunk.getMetadata()).containsKey("library_id");
    // ...but MetadataMode.EMBED - what an OpenAiEmbeddingModel actually sends to be embedded -
    // must be exactly the chunk text, byte for byte: CHUNK_EMBED_CONTENT_FORMATTER overrides both
    // the excluded metadata keys AND the text template (see its own Javadoc for why the template
    // override matters even with every key excluded), so this is a real whitelist - not just "no
    // metadata key substrings present" - and stays a guard against a sixth bookkeeping key ever
    // being added to storeChunks's metadata map without also being added to the exclusion list
    // above: an unlisted key would show up here as a formatted string that no longer equals
    // getText(), not as a silent, easy-to-miss near-miss.
    assertThat(storedChunk.getFormattedContent(org.springframework.ai.document.MetadataMode.EMBED))
        .isEqualTo(storedChunk.getText());
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
    verify(vectorStore, never()).add(any());
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
    verify(vectorStore).delete(documentIdFilter(existingDoc.getId()));
    verify(documentRepository).delete(existingDoc);
    verify(documentService).parseDocument(file);
  }

  @Test
  void reindexingKeepsTheLibraryAssignmentWhenTheTargetLibraryIsUnchanged() throws IOException {
    // #419 acceptance criteria: re-indexing into the same library keeps the assignment. The old
    // document row is deleted and a new one created (see reindexesDocumentWithChangedChecksum
    // above), so this pins that the replacement row still carries the chosen library, not a
    // dangling/absent one.
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
    Document newDoc = docCaptor.getAllValues().getFirst();
    assertThat(newDoc.getId()).isNotEqualTo(existingDoc.getId());
    assertThat(newDoc.getLibraryId()).isEqualTo(targetLibrary.getId());
    assertThat(newDoc.getOrganizationId()).isEqualTo(targetLibrary.getOrganizationId());
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
    verify(vectorStore).add(chunkCaptor.capture());
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
    verify(documentRepository).delete(existingDoc);
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
    verify(vectorStore).add(any());

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
    verify(vectorStore, org.mockito.Mockito.times(1)).add(any());
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
  void processUrlFileReindexesChangedDocument() throws IOException {
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
    verify(documentRepository).delete(existingDoc);
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
    verify(vectorStore).add(any());
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
    when(documentRepository.markFailed(any(), any())).thenReturn(0);
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
    verify(vectorStore).add(any());
    ArgumentCaptor<Document> savedDocCaptor = ArgumentCaptor.forClass(Document.class);
    verify(documentRepository).save(savedDocCaptor.capture());
    verify(vectorStore).delete(documentIdFilter(savedDocCaptor.getValue().getId()));
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
    verify(vectorStore, never()).add(any());
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
    verify(vectorStore).add(any());
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

    verify(vectorStore).add(any());
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
    verify(vectorStore, never()).add(any());
    // Nothing was ever written for this document, so there is nothing to remove from the vector
    // store either - unlike the exception path below, which may have already written chunks.
    verify(vectorStore, never()).delete(any(Filter.Expression.class));
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
    verify(vectorStore, never()).add(any());
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
    verify(vectorStore).add(any());
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
    verify(vectorStore).add(any());
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
}
