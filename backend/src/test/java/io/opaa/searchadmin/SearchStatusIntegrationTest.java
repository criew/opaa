package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.FullTextIndexFillState;
import io.opaa.indexing.FullTextIndexFillStateService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The status display against a real Postgres - above all that the per-library full-text fill state
 * is the very number {@link FullTextIndexFillStateService} produces, not a second count with its
 * own logic (#1053 acceptance criterion 2).
 */
@OpaaIndexingIntegrationTest
class SearchStatusIntegrationTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private SearchStatusService searchStatusService;
  @Autowired private FullTextIndexFillStateService fullTextIndexFillStateService;
  @Autowired private VectorStore vectorStore;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID ownerId;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    ownerId = UUID.randomUUID();
    libraryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Status-Admin', now(),"
            + " 'SYSTEM_ADMIN', ?)",
        ownerId,
        "status-it-" + ownerId,
        "status-it-" + ownerId + "@example.com",
        DEFAULT_ORGANIZATION_ID);
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Statusbibliothek', 'USER', ?, 'PRIVATE', false, 'UPLOAD', now(),"
            + " now())",
        libraryId,
        DEFAULT_ORGANIZATION_ID,
        ownerId);
  }

  @AfterEach
  void tearDown() {
    vectorChunkStore.deleteByLibraryId(libraryId);
    jdbcTemplate.update("DELETE FROM documents WHERE library_id = ?", libraryId);
    jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", libraryId);
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerId);
  }

  @Test
  void theFullTextFillStateIsTheSameNumberTheFillStateServiceReads() {
    UUID documentId = UUID.randomUUID();
    insertDocument(documentId, "satzung.pdf", "INDEXED", 3);
    // Written straight into the vector store, bypassing VectorChunkStore - so no chunk_full_text
    // row exists and the library is exactly in the state this page must show as incomplete.
    vectorStore.add(
        List.of(chunk(documentId, "satzung.pdf", 0), chunk(documentId, "satzung.pdf", 1)));

    LibrarySearchStatus status = statusOfOwnLibrary();
    FullTextIndexFillState source = fullTextIndexFillStateService.fillStateForLibrary(libraryId);

    assertThat(status.vectorChunkCount()).isEqualTo(source.totalChunks()).isEqualTo(2);
    assertThat(status.fullTextIndexedChunks()).isEqualTo(source.indexedChunks()).isZero();
    assertThat(status.fullTextMissingChunks()).isEqualTo(source.missingChunks()).isEqualTo(2);
    assertThat(source.isComplete()).isFalse();
    assertThat(status.fullTextIndexCondition())
        .isEqualTo(LibrarySearchStatus.IndexCondition.INCOMPLETE);
  }

  @Test
  void anIndexedDocumentWithoutChunksIsCountedAsAPermanentMetric() {
    insertDocument(UUID.randomUUID(), "scan-ohne-textebene.pdf", "INDEXED", 0);
    insertDocument(UUID.randomUUID(), "satzung.pdf", "INDEXED", 7);
    insertDocument(UUID.randomUUID(), "noch-nicht-dran.pdf", "PENDING", 0);
    insertDocument(UUID.randomUUID(), "kaputt.pdf", "FAILED", 0);

    LibrarySearchStatus status = statusOfOwnLibrary();

    assertThat(status.documentCount()).isEqualTo(4);
    assertThat(status.indexedDocumentCount()).isEqualTo(2);
    assertThat(status.pendingDocumentCount()).isEqualTo(1);
    assertThat(status.failedDocumentCount()).isEqualTo(1);
    // Only the INDEXED one with zero chunks - a PENDING document without chunks is not an anomaly.
    assertThat(status.lowChunkDocumentCount()).isEqualTo(1);
    assertThat(status.chunkCount()).isEqualTo(7);
    assertThat(status.lastIndexedAt()).isNotNull();
    // Rows inserted without an extraction version are the Altbestand the backfill (#1067) selects:
    // only the INDEXED ones count, and both are pending.
    assertThat(status.metadataBackfill().totalDocuments()).isEqualTo(2);
    assertThat(status.metadataBackfill().pendingDocuments()).isEqualTo(2);
    assertThat(status.metadataBackfill().currentDocuments()).isZero();
    assertThat(status.metadataBackfill().isComplete()).isFalse();
  }

  @Test
  void aLibraryWithoutAnyDocumentStillAppearsWithZeroCounts() {
    LibrarySearchStatus status = statusOfOwnLibrary();

    assertThat(status.libraryName()).isEqualTo("Statusbibliothek");
    assertThat(status.documentCount()).isZero();
    assertThat(status.lastIndexedAt()).isNull();
    assertThat(status.vectorIndexCondition()).isEqualTo(LibrarySearchStatus.IndexCondition.EMPTY);
    assertThat(status.fullTextIndexCondition()).isEqualTo(LibrarySearchStatus.IndexCondition.EMPTY);
  }

  @Test
  void theRerankRoleIsReportedInOneOfItsThreeDistinguishableStates() {
    SearchStatus status = searchStatusService.statusForOrganization(DEFAULT_ORGANIZATION_ID);

    ModelRoleStatus rerank =
        status.modelRoles().stream()
            .filter(role -> role.role() == ModelRole.RERANK)
            .findFirst()
            .orElseThrow();
    // Without #1050's own provider the fallback reports "deliberately off" - a statement, and one
    // that is explicitly not a fault.
    assertThat(rerank.condition()).isEqualTo(ModelRoleCondition.DISABLED);
    assertThat(rerank.condition().isFault()).isFalse();
    assertThat(rerank.detail()).contains("abgeschaltet");
    assertThat(status.modelRoles())
        .extracting(ModelRoleStatus::role)
        .contains(ModelRole.CHAT, ModelRole.EMBEDDING, ModelRole.RERANK);
  }

  @Test
  void bothSearchPathsAreReportedWithAState() {
    SearchStatus status = searchStatusService.statusForOrganization(DEFAULT_ORGANIZATION_ID);

    assertThat(status.searchPaths())
        .extracting(SearchPathStatus::path)
        .containsExactly(
            SearchPathStatus.SearchPathName.VECTOR, SearchPathStatus.SearchPathName.FULL_TEXT);
    assertThat(status.searchPaths()).allSatisfy(path -> assertThat(path.condition()).isNotNull());
  }

  private LibrarySearchStatus statusOfOwnLibrary() {
    return searchStatusService.statusForOrganization(DEFAULT_ORGANIZATION_ID).libraries().stream()
        .filter(library -> library.libraryId().equals(libraryId))
        .findFirst()
        .orElseThrow();
  }

  private void insertDocument(UUID documentId, String fileName, String status, int chunkCount) {
    jdbcTemplate.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, 'application/pdf', 1024, ?, now(), ?, ?, 'UPLOAD', ?, ?, now())",
        documentId,
        fileName,
        "status-it/" + documentId,
        chunkCount,
        "checksum-" + documentId,
        status,
        libraryId,
        DEFAULT_ORGANIZATION_ID);
  }

  private Document chunk(UUID documentId, String fileName, int chunkIndex) {
    return new Document(
        "Gebührenbefreiung wegen Bedürftigkeit, Abschnitt " + chunkIndex,
        Map.of(
            "file_name",
            fileName,
            "document_id",
            documentId.toString(),
            "chunk_index",
            chunkIndex,
            "library_id",
            libraryId.toString()));
  }
}
