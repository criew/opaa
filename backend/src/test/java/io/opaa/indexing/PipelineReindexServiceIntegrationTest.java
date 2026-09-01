package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The selective re-index by pipeline version (#1056, ingestion-pipelines.md Querschnittsregel (d)):
 * "alle Chunks unterhalb Version N dieser Pipeline" must be auslösbar, wiederaufnehmbar, and its
 * Fortschritt je Bibliothek abfragbar.
 *
 * <p>Chunks are seeded directly through {@link VectorStore#add} without the pipeline metadata -
 * that is exactly the state of every chunk written before the abstraction existed, the bestand the
 * whole mechanism is meant to be able to reach again.
 */
@OpaaIndexingIntegrationTest
class PipelineReindexServiceIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("pipeline-reindex");

  @Autowired private PipelineReindexService reindexService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  private UUID userId;
  private KnowledgeLibrary library;
  private KnowledgeLibrary uploadLibrary;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    documentRepository.deleteAll();
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'pipeline-reindex-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'pipeline-reindex-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'pipeline-reindex-it@example.com',"
            + " 'Pipeline Reindex IT User', now(), ?, ?)",
        userId,
        "pipeline-reindex-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    // A FILESYSTEM library, because sourcePath is what a FILESYSTEM document's file must resolve
    // underneath before the re-index is allowed to read it again (ADR-0018, Entscheidung 6).
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Zielbibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                classTempDir.toString(),
                null,
                null,
                null,
                false));
    uploadLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Uploadbibliothek",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false));
  }

  @Test
  void thePreAbstractionBestandCountsAsStaleAndIsReportedPerLibrary() throws IOException {
    Document legacy = persistedFilesystemDocument("altbestand.txt", "Alter Inhalt");
    seedChunk(legacy.getId(), "alter chunk", null, null);
    Document current = persistedFilesystemDocument("neubestand.txt", "Neuer Inhalt");
    seedChunk(
        current.getId(), "neuer chunk", TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION);

    List<PipelineVersionProgress> progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID);

    assertThat(progress).hasSize(1);
    PipelineVersionProgress libraryProgress = progress.getFirst();
    assertThat(libraryProgress.libraryId()).isEqualTo(library.getId());
    assertThat(libraryProgress.totalChunks()).isEqualTo(2);
    assertThat(libraryProgress.currentVersionChunks()).isEqualTo(1);
    // A chunk carrying no pipeline metadata at all is not "unbekannt" but attributable: only the
    // Tika path ever produced chunks before, so it counts as that pipeline at version 0.
    assertThat(libraryProgress.staleChunks()).isEqualTo(1);
    assertThat(libraryProgress.isComplete()).isFalse();
  }

  @Test
  void aChunkNamingAnUnknownPipelineIsNeitherCurrentNorStale() throws IOException {
    Document document = persistedFilesystemDocument("fremd.txt", "Inhalt");
    seedChunk(document.getId(), "fremder chunk", "docling-pdf", (short) 4);

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();

    assertThat(progress.totalChunks()).isEqualTo(1);
    assertThat(progress.currentVersionChunks()).isZero();
    assertThat(progress.staleChunks()).isZero();
    assertThat(progress.isComplete()).isTrue();
  }

  @Test
  void aLocallyReadableDocumentIsReindexedInPlaceAndKeepsItsDocumentId() throws IOException {
    Document document =
        persistedFilesystemDocument(
            "satzung.txt", "Die Verwaltungsgebühr für einen Personalausweis beträgt 37,00 EUR.");
    seedChunk(document.getId(), "veralteter chunk", null, null);

    PipelineReindexResult result =
        reindexService.reindexBatch(
            Organization.DEFAULT_ID, TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION, 10);

    assertThat(result.reindexedDocuments()).isEqualTo(1);
    assertThat(result.isEmpty()).isFalse();
    // Same document row, new chunks: citations and deep links into the document survive.
    assertThat(documentRepository.findById(document.getId())).isPresent();
    assertThat(chunkTextsOf(document.getId()))
        .noneMatch(text -> text.equals("veralteter chunk"))
        .isNotEmpty();
    assertThat(pipelineVersionsOf(document.getId()))
        .containsOnly((int) TikaFallbackPipeline.VERSION);
    // The re-index is the first path that deletes and rewrites chunks of an existing bestand, so
    // the lexical index has to come along: exactly one chunk_full_text row per vector_store chunk
    // of this document, none left over from the chunks that were replaced.
    assertThat(fullTextRowCountOf(document.getId()))
        .isEqualTo(chunkTextsOf(document.getId()).size());

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();
    assertThat(progress.staleChunks()).isZero();
    assertThat(progress.isComplete()).isTrue();

    // Drained: the next call finds nothing left, the signal to stop repeating.
    assertThat(
            reindexService
                .reindexBatch(
                    Organization.DEFAULT_ID,
                    TikaFallbackPipeline.ID,
                    TikaFallbackPipeline.VERSION,
                    10)
                .isEmpty())
        .isTrue();
  }

  @Test
  void repeatedSmallBatchesDrainTheBacklogWithoutRedoingFinishedWork() throws IOException {
    for (int i = 0; i < 3; i++) {
      Document document = persistedFilesystemDocument("dokument-" + i + ".txt", "Inhalt " + i);
      seedChunk(document.getId(), "alter chunk " + i, null, null);
    }

    // Simulates an interrupted, then resumed run: nothing but the chunk metadata itself remembers
    // where the previous call left off.
    assertThat(reindexBatch(2).reindexedDocuments()).isEqualTo(2);
    assertThat(reindexBatch(2).reindexedDocuments()).isEqualTo(1);
    assertThat(reindexBatch(2).isEmpty()).isTrue();

    PipelineVersionProgress progress =
        reindexService.progressForOrganization(Organization.DEFAULT_ID).getFirst();
    assertThat(progress.staleChunks()).isZero();
    assertThat(progress.currentVersionChunks()).isEqualTo(progress.totalChunks());
  }

  @Test
  void aRemoteDocumentIsMarkedForItsNextRunOnceAndThenLeavesTheBacklog() {
    Document document = persistedRemoteDocument("https://example.test/satzung.pdf");
    seedChunk(document.getId(), "alter chunk", null, null);

    PipelineReindexResult first = reindexBatch(10);

    assertThat(first.markedForNextRun()).isEqualTo(1);
    assertThat(first.reindexedDocuments()).isZero();
    // Clearing the checksum is what stops processUrlFile from treating it as unchanged.
    assertThat(documentRepository.findById(document.getId()).orElseThrow().getChecksum()).isNull();

    // Its chunks are still stale (only the connector run can re-read the source), which the
    // progress figures keep showing - but the batch itself drains instead of reselecting it
    // forever.
    assertThat(reindexBatch(10).isEmpty()).isTrue();
    assertThat(
            reindexService
                .progressForOrganization(Organization.DEFAULT_ID)
                .getFirst()
                .staleChunks())
        .isEqualTo(1);
  }

  @Test
  void aFileOutsideTheLibrarysConfiguredDirectoryIsNeverReadAgain() throws IOException {
    // ADR-0018, Entscheidung 6: file_path was validated when the document was indexed, but the
    // allowlist and the library's own sourcePath can be narrowed afterwards. A re-index must not be
    // the one path that silently keeps reading a file the operator has since withdrawn.
    Path outside = Files.createTempDirectory("outside-allowlist").resolve("geheim.txt");
    Files.writeString(outside, "Inhalt außerhalb des konfigurierten Verzeichnisses. ".repeat(20));
    Document document =
        persistedDocumentPointingAt(
            "geheim.txt", outside, DocumentSourceType.FILESYSTEM, library.getId());
    seedChunk(document.getId(), "alter chunk", null, null);

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.skippedDocuments()).isEqualTo(1);
    assertThat(result.reindexedDocuments()).isZero();
    // The old chunk is still there untouched - the file was never opened, so there was nothing to
    // replace it with, and destroying it would have been the worse outcome.
    assertThat(chunkTextsOf(document.getId())).containsExactly("alter chunk");
    // A call that only skipped is the signal to stop; the outstanding chunk stays visible.
    assertThat(result.isEmpty()).isTrue();
    assertThat(
            reindexService
                .progressForOrganization(Organization.DEFAULT_ID)
                .getFirst()
                .staleChunks())
        .isEqualTo(1);
  }

  @Test
  void anUploadedFileOutsideTheManagedStorageIsNeverReadAgain() throws IOException {
    Path outside = Files.createTempDirectory("outside-upload-storage").resolve("fremd.txt");
    Files.writeString(outside, "Nicht von diesem Dienst geschrieben. ".repeat(20));
    Document document =
        persistedDocumentPointingAt(
            "fremd.txt", outside, DocumentSourceType.UPLOAD, uploadLibrary.getId());
    seedChunk(document.getId(), uploadLibrary.getId(), "alter chunk", null, null);

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.skippedDocuments()).isEqualTo(1);
    assertThat(chunkTextsOf(document.getId())).containsExactly("alter chunk");
  }

  @Test
  void aMarkedRemoteDocumentIsActuallyReprocessedByItsOwnConnectorRun() {
    // The gate that decides it, before anything is downloaded: UrlIndexingExecutor#isUnchanged
    // reads last_modified_remote plus INDEXED - never the checksum, because the bytes it would be
    // computed from have deliberately not been fetched yet. Clearing the checksum alone would
    // therefore have been a no-op the run never notices.
    String remoteUrl = "https://example.test/satzung.pdf";
    String lastModified = "Tue, 01 Sep 2026 06:00:00 GMT";
    Document document = persistedRemoteDocument(remoteUrl);
    document.setLastModifiedRemote(lastModified);
    document.setStatus(DocumentStatus.INDEXED);
    documentRepository.save(document);
    seedChunk(document.getId(), "alter chunk", null, null);

    UrlIndexingExecutor executor = urlIndexingExecutorForGateCheck();
    assertThat(executor.isUnchanged(remoteUrl, lastModified, library))
        .as("before the re-index the run would skip this document as unchanged")
        .isTrue();

    assertThat(reindexBatch(10).markedForNextRun()).isEqualTo(1);

    assertThat(executor.isUnchanged(remoteUrl, lastModified, library))
        .as("after being marked the very same run re-reads it instead of skipping it")
        .isFalse();
    Document marked = documentRepository.findById(document.getId()).orElseThrow();
    assertThat(marked.getLastModifiedRemote()).isNull();
    // The second gate, inside processUrlFile once the file has actually been downloaded.
    assertThat(marked.getChecksum()).isNull();
  }

  /**
   * The real {@link UrlIndexingExecutor}, with only the collaborators its change decision does not
   * touch mocked away - the decision itself runs against this test's own database rows, not a
   * reimplementation of the rule.
   */
  private UrlIndexingExecutor urlIndexingExecutorForGateCheck() {
    return new UrlIndexingExecutor(
        org.mockito.Mockito.mock(AutoindexCrawlerService.class),
        org.mockito.Mockito.mock(io.opaa.sourceaccess.BoundedDownloader.class),
        org.mockito.Mockito.mock(FileProcessingService.class),
        org.mockito.Mockito.mock(IndexingJobService.class),
        documentRepository,
        org.mockito.Mockito.mock(IndexingRunEventRepository.class),
        org.mockito.Mockito.mock(io.opaa.library.LibraryStorageQuotaService.class),
        org.mockito.Mockito.mock(StaleDocumentCleanupService.class));
  }

  @Test
  void chunksWhoseDocumentRowIsGoneAreRemovedRatherThanReselectedForever() {
    UUID vanishedDocumentId = UUID.randomUUID();
    seedChunk(vanishedDocumentId, "verwaister chunk", null, null);

    PipelineReindexResult result = reindexBatch(10);

    assertThat(result.removedOrphanChunkSets()).isEqualTo(1);
    assertThat(chunkTextsOf(vanishedDocumentId)).isEmpty();
    assertThat(reindexBatch(10).isEmpty()).isTrue();
  }

  private PipelineReindexResult reindexBatch(int batchSize) {
    return reindexService.reindexBatch(
        Organization.DEFAULT_ID, TikaFallbackPipeline.ID, TikaFallbackPipeline.VERSION, batchSize);
  }

  private Document persistedFilesystemDocument(String fileName, String content) throws IOException {
    Path file = classTempDir.resolve(UUID.randomUUID() + "-" + fileName);
    Files.writeString(file, content.repeat(20));
    Document document =
        new Document(fileName, file.toAbsolutePath().toString(), "text/plain", Files.size(file));
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-" + fileName);
    return documentRepository.save(document);
  }

  private Document persistedDocumentPointingAt(
      String fileName, Path file, DocumentSourceType sourceType, UUID libraryId)
      throws IOException {
    Document document =
        new Document(
            fileName, file.toAbsolutePath().toString(), "text/plain", Files.size(file), sourceType);
    document.setLibraryId(libraryId);
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-" + fileName);
    return documentRepository.save(document);
  }

  private Document persistedRemoteDocument(String url) {
    Document document =
        new Document(
            "satzung.pdf", url, "application/pdf", 1024L, DocumentSourceType.HTTP_DIRECTORY);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    document.setChecksum("checksum-remote");
    return documentRepository.save(document);
  }

  private void seedChunk(UUID documentId, String text, String pipelineId, Short pipelineVersion) {
    seedChunk(documentId, library.getId(), text, pipelineId, pipelineVersion);
  }

  private void seedChunk(
      UUID documentId, UUID libraryId, String text, String pipelineId, Short pipelineVersion) {
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString());
    metadata.put(VectorChunkStore.LIBRARY_ID_METADATA_KEY, libraryId.toString());
    metadata.put("organization_id", Organization.DEFAULT_ID.toString());
    if (pipelineId != null) {
      metadata.put(ChunkPipelineMetadata.PIPELINE_ID_METADATA_KEY, pipelineId);
      metadata.put(ChunkPipelineMetadata.PIPELINE_VERSION_METADATA_KEY, (int) pipelineVersion);
    }
    vectorStore.add(List.of(new org.springframework.ai.document.Document(text, metadata)));
  }

  private List<String> chunkTextsOf(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT content FROM vector_store WHERE metadata->>'document_id' = ?",
        String.class,
        documentId.toString());
  }

  private long fullTextRowCountOf(UUID documentId) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM chunk_full_text WHERE document_id = ?", Long.class, documentId);
  }

  private List<Integer> pipelineVersionsOf(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT (metadata->>'pipeline_version')::int FROM vector_store "
            + "WHERE metadata->>'document_id' = ?",
        Integer.class,
        documentId.toString());
  }
}
