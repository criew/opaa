package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.knowledge.FilePathLimit;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Values a connector takes from its source, whatever their length, against the Flyway schema:
 * container key and file name are stored as they come; a file path beyond {@link FilePathLimit} -
 * the unique index on {@code (library_id, file_path)} sets it - is rejected before any write
 * instead of failing at the database.
 */
@OpaaIntegrationTest
class SourceValueLengthIntegrationTest {

  private static final SourceType DROPBOX = SourceType.of("DROPBOX");

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
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Source Value IT User', now(),"
            + " ?, ?)",
        userId,
        "source-value-it-" + userId,
        "source-value-it-" + userId + "@example.com",
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(Organization.DEFAULT_ID, "Quellwerte", null, userId));
  }

  @AfterEach
  void removeOwnRows() {
    if (library != null) {
      ownLibraryFixtures.removeLibraries(library.getId());
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  /** A Dropbox folder path is unbounded, an S3 prefix may take 1024 characters. */
  @Test
  void aContainerKeyBeyondTwoHundredFiftyFiveCharactersIsStored() throws Exception {
    String containerKey = "/Projekte/" + "Bauabschnitt Nord/".repeat(60);
    assertThat(containerKey.length()).isGreaterThan(1000);
    String filePath = "dropbox:/berichte/" + UUID.randomUUID() + ".txt";

    DocumentIngestResult result =
        documentIngestService.ingest(
            DocumentIngest.text(library, filePath, "Der Jahresbericht des Bauamts.")
                .sourceType(DROPBOX)
                .context(new SourceDocumentContext(containerKey, "Projekte"))
                .build(),
            null);

    assertThat(result).isEqualTo(DocumentIngestResult.PROCESSED);
    assertThat(stored("source_container_key", filePath)).isEqualTo(containerKey);
  }

  /** A mail or archive names its attachments freely; a source file name is not bounded either. */
  @Test
  void aFileNameBeyondFiveHundredCharactersIsStored() throws Exception {
    String fileName = "Protokoll der Sitzung ".repeat(30) + ".txt";
    assertThat(fileName.length()).isGreaterThan(500);
    String filePath = "dropbox:/protokolle/" + UUID.randomUUID() + ".txt";

    DocumentIngestResult result =
        documentIngestService.ingest(
            DocumentIngest.text(library, filePath, "Die Sitzung beginnt um zehn Uhr.")
                .fileName(fileName)
                .sourceType(DROPBOX)
                .build(),
            null);

    assertThat(result).isEqualTo(DocumentIngestResult.PROCESSED);
    assertThat(stored("file_name", filePath)).isEqualTo(fileName);
  }

  @Test
  void aFilePathBeyondTheLimitIsRejectedWithoutARow() throws Exception {
    String filePath = "dropbox:/" + "tiefer ordner/".repeat(150) + "bericht.txt";
    assertThat(FilePathLimit.fits(filePath)).isFalse();

    DocumentIngestResult result =
        documentIngestService.ingest(
            DocumentIngest.text(library, filePath, "Der Bericht liegt sehr tief.")
                .sourceType(DROPBOX)
                .build(),
            null);

    assertThat(result).isEqualTo(DocumentIngestResult.FILE_PATH_TOO_LONG);
    assertThat(documentCount()).isZero();
  }

  @Test
  void aFilePathBeyondTheLimitIsRejectedBeforeTheDownload() {
    String filePath = "dropbox:/" + "tiefer ordner/".repeat(150) + "bericht.pdf";

    assertThat(documentIngestService.rejectionBeforeDownload(library, filePath, 1024))
        .contains(DocumentIngestResult.FILE_PATH_TOO_LONG);
    assertThat(documentIngestService.rejectionBeforeDownload(library, filePath, -1))
        .contains(DocumentIngestResult.FILE_PATH_TOO_LONG);
  }

  private String stored(String column, String filePath) {
    return jdbcTemplate.queryForObject(
        "SELECT " + column + " FROM documents WHERE library_id = ? AND file_path = ?",
        String.class,
        library.getId(),
        filePath);
  }

  private long documentCount() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM documents WHERE library_id = ?", Long.class, library.getId());
  }
}
