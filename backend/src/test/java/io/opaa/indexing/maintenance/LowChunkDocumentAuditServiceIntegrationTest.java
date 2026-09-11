package io.opaa.indexing.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link LowChunkDocumentAuditService} against a real Liquibase schema: proves the organization
 * scope, the {@code status = INDEXED} filter and the {@code chunkCount} threshold are genuinely
 * enforced by the query, not merely assumed from {@link DocumentRepository}'s derived method name -
 * and that {@code idx_documents_indexed_chunk_count} (migration 002) is actually usable by it (same
 * columns/predicate the query filters on).
 */
@OpaaIntegrationTest
class LowChunkDocumentAuditServiceIntegrationTest {

  private static final String OWNER_EMAIL = "low-chunk-audit-it@example.com";
  private static final String OTHER_ORGANIZATION_OWNER_EMAIL =
      "low-chunk-audit-it-other-org@example.com";
  // Deterministic, not random: setUp deletes by these ids before inserting, so leftovers from a
  // prior test method (or a prior interrupted run) are cleaned up rather than accumulating one
  // orphan organization per test invocation.
  //
  // An own organization rather than the shared default one: findLowChunkDocuments answers for a
  // whole organization, and the assertions below name the documents it returns exactly. In the
  // shared default organization they would see whatever a neighbouring class has indexed.
  private static final UUID ORGANIZATION_ID =
      UUID.fromString("11111111-2222-3333-4444-000000000001");
  private static final UUID OTHER_ORGANIZATION_ID =
      UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Autowired private LowChunkDocumentAuditService lowChunkDocumentAuditService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private KnowledgeLibrary library;
  private KnowledgeLibrary otherOrganizationLibrary;

  @BeforeEach
  void setUp() {
    cleanUpFixtures();
    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, 'Pruefstelle', now())",
        ORGANIZATION_ID);
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Low Chunk Audit IT User', now(),"
            + " ?, ?)",
        userId,
        "low-chunk-audit-it-" + userId,
        OWNER_EMAIL,
        SystemRole.SYSTEM_ADMIN.name(),
        ORGANIZATION_ID);

    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                ORGANIZATION_ID, "Satzungen", null, userId, LibraryVisibility.PRIVATE, false));

    // A document's organizationId is always denormalized from its own library's (see
    // DocumentIngestService#processFile) - cross-org scoping is genuinely tested only against a
    // second, independent organization/user/library, not by attaching a foreign organizationId to
    // the same library (which the fk_documents_library_organization composite FK rejects anyway).
    jdbcTemplate.update(
        "INSERT INTO organizations (id, name, created_at) VALUES (?, 'Fremdorganisation', now())",
        OTHER_ORGANIZATION_ID);
    UUID otherOrganizationUserId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Fremdorganisation User', now(),"
            + " ?, ?)",
        otherOrganizationUserId,
        "low-chunk-audit-it-other-org-" + otherOrganizationUserId,
        OTHER_ORGANIZATION_OWNER_EMAIL,
        SystemRole.SYSTEM_ADMIN.name(),
        OTHER_ORGANIZATION_ID);
    otherOrganizationLibrary =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                OTHER_ORGANIZATION_ID,
                "Fremdbibliothek",
                null,
                otherOrganizationUserId,
                LibraryVisibility.PRIVATE,
                false));
  }

  @AfterEach
  void tearDown() {
    // The whole suite shares one database: what this class created goes away with it, scoped to
    // its own two libraries, their users and the throwaway organization.
    cleanUpFixtures();
  }

  /**
   * Deletes every fixture this class creates, in FK order: documents, libraries, users, org. Found
   * through the two owner e-mails rather than through the fields above, because this also runs in
   * {@code @BeforeEach}, where the libraries of an aborted previous method are the ones to remove.
   */
  private void cleanUpFixtures() {
    for (String ownerEmail : List.of(OWNER_EMAIL, OTHER_ORGANIZATION_OWNER_EMAIL)) {
      List<UUID> ownLibraryIds =
          jdbcTemplate.queryForList(
              "SELECT id FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users"
                  + " WHERE email = ?)",
              UUID.class,
              ownerEmail);
      ownLibraryFixtures.removeLibraries(ownLibraryIds.toArray(new UUID[0]));
      jdbcTemplate.update("DELETE FROM users WHERE email = ?", ownerEmail);
    }
    jdbcTemplate.update(
        "DELETE FROM organizations WHERE id IN (?, ?)", ORGANIZATION_ID, OTHER_ORGANIZATION_ID);
  }

  private Document indexedDocument(
      KnowledgeLibrary targetLibrary, String fileName, int chunkCount) {
    Document document = new Document(fileName, "/path/" + fileName, "application/pdf", 100L);
    document.setLibraryId(targetLibrary.getId());
    document.setOrganizationId(targetLibrary.getOrganizationId());
    document.setStatus(DocumentStatus.INDEXED);
    document.setChunkCount(chunkCount);
    return documentRepository.save(document);
  }

  @Test
  void findsOnlyIndexedDocumentsAtOrBelowTheThresholdInTheCallersOwnOrganization() {
    indexedDocument(library, "scan.pdf", 0);
    Document withEnoughChunks = indexedDocument(library, "ok.pdf", 5);
    Document notIndexed = indexedDocument(library, "pending.pdf", 0);
    notIndexed.setStatus(DocumentStatus.PENDING);
    documentRepository.save(notIndexed);
    indexedDocument(otherOrganizationLibrary, "foreign-org-scan.pdf", 0);

    Page<LowChunkDocumentAuditService.LowChunkDocumentEntry> result =
        lowChunkDocumentAuditService.findLowChunkDocuments(
            ORGANIZATION_ID, 0, PageRequest.of(0, 20));

    assertThat(result.getContent())
        .extracting(LowChunkDocumentAuditService.LowChunkDocumentEntry::fileName)
        .containsExactly("scan.pdf");
    assertThat(result.getContent().getFirst().libraryName()).isEqualTo("Satzungen");
    assertThat(withEnoughChunks.getChunkCount()).isEqualTo(5);
  }

  @Test
  void raisingTheThresholdAlsoCatchesAuffaelligWenigeChunks() {
    indexedDocument(library, "near-empty.pdf", 2);
    indexedDocument(library, "healthy.pdf", 20);

    Page<LowChunkDocumentAuditService.LowChunkDocumentEntry> result =
        lowChunkDocumentAuditService.findLowChunkDocuments(
            ORGANIZATION_ID, 3, PageRequest.of(0, 20));

    assertThat(result.getContent())
        .extracting(LowChunkDocumentAuditService.LowChunkDocumentEntry::fileName)
        .containsExactly("near-empty.pdf");
  }

  @Test
  void pagesTheResultAccordingToTheGivenPageable() {
    for (int i = 0; i < 3; i++) {
      indexedDocument(library, "scan-" + i + ".pdf", 0);
    }

    Page<LowChunkDocumentAuditService.LowChunkDocumentEntry> firstPage =
        lowChunkDocumentAuditService.findLowChunkDocuments(
            ORGANIZATION_ID, 0, PageRequest.of(0, 2));

    assertThat(firstPage.getContent()).hasSize(2);
    assertThat(firstPage.getTotalElements()).isEqualTo(3);
    assertThat(firstPage.getTotalPages()).isEqualTo(2);
  }
}
