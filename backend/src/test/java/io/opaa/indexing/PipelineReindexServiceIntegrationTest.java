package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentSourceType;
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
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Zielbibliothek",
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
    Map<String, Object> metadata = new HashMap<>();
    metadata.put(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, documentId.toString());
    metadata.put(VectorChunkStore.LIBRARY_ID_METADATA_KEY, library.getId().toString());
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

  private List<Integer> pipelineVersionsOf(UUID documentId) {
    return jdbcTemplate.queryForList(
        "SELECT (metadata->>'pipeline_version')::int FROM vector_store "
            + "WHERE metadata->>'document_id' = ?",
        Integer.class,
        documentId.toString());
  }
}
