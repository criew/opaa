package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A connector's change marker is stored as it comes, whatever its length: the ingest runs against
 * the Flyway schema and reads {@code documents.last_modified_remote} back unchanged.
 */
@OpaaIntegrationTest
class DocumentChangeMarkerPersistenceIntegrationTest {

  /** A Dropbox {@code content_hash} is SHA-256 in hex; the store prefixes it with {@code h:}. */
  private static final String DROPBOX_CONTENT_HASH_MARKER =
      "h:" + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'change-marker-it@example.com',"
            + " 'Change Marker IT User', now(), ?, ?)",
        userId,
        "change-marker-it-" + userId,
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID, "Änderungsmerkmal", null, userId));
  }

  @AfterEach
  void removeOwnRows() {
    if (library != null) {
      ownLibraryFixtures.removeLibraries(library.getId());
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        DROPBOX_CONTENT_HASH_MARKER,
        // an autoindex listing's date cell is free text of a foreign server
        "Montag, 6. Oktober 2026, 14:03:12 Mitteleuropäische Sommerzeit (aus dem Archivbestand)"
      })
  void aMarkerLongerThanSixtyFourCharactersIsStored(String marker) throws Exception {
    assertThat(marker.length()).isGreaterThan(64);
    String filePath = "dropbox:/berichte/" + UUID.randomUUID() + ".txt";

    DocumentIngestResult result =
        documentIngestService.ingest(
            DocumentIngest.text(library, filePath, "Der Jahresbericht des Bauamts.")
                .sourceType(SourceType.of("DROPBOX"))
                .changeMarker(marker)
                .build(),
            null);

    assertThat(result).isEqualTo(DocumentIngestResult.PROCESSED);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT last_modified_remote FROM documents WHERE library_id = ? AND file_path = ?",
                String.class,
                library.getId(),
                filePath))
        .isEqualTo(marker);
  }
}
