package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.NotFoundException;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link ChunkInspectionService} against a real Postgres: chunk order comes from {@code
 * chunk_index}, the organization boundary is decided by the {@code documents} row (a chunk whose
 * document is gone or foreign is absent), and the embedding column is never read.
 */
@OpaaIntegrationTest
class ChunkInspectionServiceIntegrationTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ChunkInspectionService service;

  private UUID userId;
  private UUID foreignUserId;
  private UUID foreignOrganizationId;
  private UUID libraryId;
  private UUID foreignLibraryId;
  private UUID documentId;
  private UUID foreignDocumentId;
  private final List<UUID> chunkIds = new java.util.ArrayList<>();
  private UUID stringIndexChunkId;
  private UUID indexlessChunkId;
  private UUID orphanChunkId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    foreignUserId = UUID.randomUUID();
    foreignOrganizationId = UUID.randomUUID();
    libraryId = UUID.randomUUID();
    foreignLibraryId = UUID.randomUUID();
    documentId = UUID.randomUUID();
    foreignDocumentId = UUID.randomUUID();
    stringIndexChunkId = UUID.randomUUID();
    indexlessChunkId = UUID.randomUUID();
    orphanChunkId = UUID.randomUUID();
    chunkIds.clear();

    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, 'Fremdorganisation', now())",
        foreignOrganizationId);
    insertUser(userId, DEFAULT_ORGANIZATION_ID);
    insertUser(foreignUserId, foreignOrganizationId);
    insertLibrary(libraryId, DEFAULT_ORGANIZATION_ID, "Satzungen", userId);
    insertLibrary(foreignLibraryId, foreignOrganizationId, "Fremde Satzungen", foreignUserId);
    // chunk_count deliberately disagrees with the stored rows (7 vs. 5): the listing must report
    // the entity's number, not count the rows again.
    insertDocument(documentId, DEFAULT_ORGANIZATION_ID, libraryId, "satzung.pdf", 7);
    insertDocument(foreignDocumentId, foreignOrganizationId, foreignLibraryId, "fremd.pdf", 1);

    // Inserted out of order on purpose.
    for (int index : new int[] {2, 0, 1}) {
      UUID chunkId = UUID.randomUUID();
      chunkIds.add(chunkId);
      insertChunk(chunkId, documentId, index, "Abschnitt " + index + "\nmit Zeilenumbruch");
    }
    // Inserted before the numeric rows would sort it, and as a JSON string rather than a number.
    insertChunk(stringIndexChunkId, documentId, "\"3\"", "Abschnitt 3 mit Index als Text");
    insertChunk(indexlessChunkId, documentId, null, "Abschnitt ohne Index");
    insertChunk(orphanChunkId, UUID.randomUUID(), 0, "Rest eines gelöschten Dokuments");
  }

  @AfterEach
  void tearDown() {
    List<UUID> allChunkIds = new java.util.ArrayList<>(chunkIds);
    allChunkIds.add(stringIndexChunkId);
    allChunkIds.add(indexlessChunkId);
    allChunkIds.add(orphanChunkId);
    for (UUID chunkId : allChunkIds) {
      jdbcTemplate.update("DELETE FROM public.vector_store WHERE id = ?", chunkId);
    }
    jdbcTemplate.update("DELETE FROM documents WHERE id in (?, ?)", documentId, foreignDocumentId);
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE id in (?, ?)", libraryId, foreignLibraryId);
    jdbcTemplate.update("DELETE FROM users WHERE id in (?, ?)", userId, foreignUserId);
    jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", foreignOrganizationId);
  }

  @Test
  void findChunkReturnsTextMetadataAndTheResolvedDocument() {
    UUID chunkId = chunkIds.get(1); // stored with chunk_index 0

    ChunkInspection chunk =
        service.findChunk(DEFAULT_ORGANIZATION_ID, chunkId.toString()).orElseThrow();

    assertThat(chunk.chunkId()).isEqualTo(chunkId.toString());
    assertThat(chunk.documentId()).isEqualTo(documentId);
    assertThat(chunk.documentTitle()).isEqualTo("satzung.pdf");
    assertThat(chunk.libraryId()).isEqualTo(libraryId);
    assertThat(chunk.libraryName()).isEqualTo("Satzungen");
    assertThat(chunk.chunkIndex()).isZero();
    assertThat(chunk.content()).isEqualTo("Abschnitt 0\nmit Zeilenumbruch");
    assertThat(chunk.metadata())
        .containsEntry("document_id", documentId.toString())
        .containsEntry("chunk_index", 0)
        .containsEntry("location", "Seite 1")
        .doesNotContainKey("embedding");
  }

  @Test
  void findChunkReadsAChunkIndexStoredAsTextAndReportsAMissingOneAsNull() {
    ChunkInspection textIndexed =
        service.findChunk(DEFAULT_ORGANIZATION_ID, stringIndexChunkId.toString()).orElseThrow();
    assertThat(textIndexed.chunkIndex()).isEqualTo(3);

    ChunkInspection indexless =
        service.findChunk(DEFAULT_ORGANIZATION_ID, indexlessChunkId.toString()).orElseThrow();
    assertThat(indexless.chunkIndex()).isNull();
    assertThat(indexless.metadata()).doesNotContainKey("chunk_index");
  }

  @Test
  void findChunkIsEmptyForAnotherOrganization() {
    assertThat(service.findChunk(foreignOrganizationId, chunkIds.get(0).toString())).isEmpty();
  }

  @Test
  void findChunkIsEmptyForAnUnknownIdAndForAChunkWhoseDocumentIsGone() {
    assertThat(service.findChunk(DEFAULT_ORGANIZATION_ID, UUID.randomUUID().toString())).isEmpty();
    assertThat(service.findChunk(DEFAULT_ORGANIZATION_ID, "kein-uuid")).isEmpty();
    assertThat(service.findChunk(DEFAULT_ORGANIZATION_ID, orphanChunkId.toString())).isEmpty();
  }

  @Test
  void listDocumentChunksOrdersByChunkIndexAndReportsTheEntitysChunkCount() {
    DocumentChunks chunks = service.listDocumentChunks(DEFAULT_ORGANIZATION_ID, documentId);

    assertThat(chunks.documentTitle()).isEqualTo("satzung.pdf");
    assertThat(chunks.libraryName()).isEqualTo("Satzungen");
    assertThat(chunks.chunkCount()).isEqualTo(7);
    // Numeric and text-stored indexes sort together; a chunk without one comes last.
    assertThat(chunks.chunks())
        .extracting(ChunkInspection::chunkIndex)
        .containsExactly(0, 1, 2, 3, null);
    assertThat(chunks.chunks())
        .extracting(ChunkInspection::content)
        .containsExactly(
            "Abschnitt 0\nmit Zeilenumbruch",
            "Abschnitt 1\nmit Zeilenumbruch",
            "Abschnitt 2\nmit Zeilenumbruch",
            "Abschnitt 3 mit Index als Text",
            "Abschnitt ohne Index");
  }

  @Test
  void listDocumentChunksRejectsAForeignOrUnknownDocumentAsNotFound() {
    assertThatThrownBy(() -> service.listDocumentChunks(DEFAULT_ORGANIZATION_ID, foreignDocumentId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.listDocumentChunks(foreignOrganizationId, documentId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.listDocumentChunks(DEFAULT_ORGANIZATION_ID, UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
  }

  private void insertUser(UUID id, UUID organizationId) {
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Chunk-Admin', now(),"
            + " 'SYSTEM_ADMIN', ?)",
        id,
        "chunk-it-" + id,
        "chunk-it-" + id + "@example.com",
        organizationId);
  }

  private void insertLibrary(UUID id, UUID organizationId, String name, UUID ownerUserId) {
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', false, 'UPLOAD', now(), now())",
        id,
        organizationId,
        name,
        ownerUserId);
  }

  private void insertDocument(
      UUID id, UUID organizationId, UUID ownerLibraryId, String fileName, int chunkCount) {
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, 'application/pdf', 1024, ?, now(), ?, 'INDEXED', 'UPLOAD', ?, ?,"
            + " now())",
        id,
        fileName,
        "chunk-it/" + fileName + "/" + id,
        chunkCount,
        "checksum-" + id,
        ownerLibraryId,
        organizationId);
  }

  private void insertChunk(UUID chunkId, UUID ownerDocumentId, int chunkIndex, String content) {
    insertChunk(chunkId, ownerDocumentId, Integer.toString(chunkIndex), content);
  }

  /** {@code chunkIndexJson} is spliced in verbatim (number, quoted string) or omitted when null. */
  private void insertChunk(
      UUID chunkId, UUID ownerDocumentId, String chunkIndexJson, String content) {
    String indexEntry = chunkIndexJson == null ? "" : ",\"chunk_index\":" + chunkIndexJson;
    String metadata =
        "{\"document_id\":\"%s\"%s,\"library_id\":\"%s\",\"file_name\":\"satzung.pdf\",\"location\":\"Seite 1\"}"
            .formatted(ownerDocumentId, indexEntry, libraryId);
    // No embedding: the read path must not depend on the column, and the row must still be found.
    jdbcTemplate.update(
        "INSERT INTO public.vector_store (id, content, metadata) VALUES (?, ?, ?::jsonb)",
        chunkId,
        content,
        metadata);
  }
}
