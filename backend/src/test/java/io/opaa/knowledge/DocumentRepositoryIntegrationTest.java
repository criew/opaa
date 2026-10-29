package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SystemRole;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.SourceTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The native queries of {@link DocumentRepository} against the Flyway schema on PostgreSQL. */
@OpaaIntegrationTest
class DocumentRepositoryIntegrationTest {

  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private final List<KnowledgeLibrary> libraries = new ArrayList<>();

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Document Repository IT', now(),"
            + " ?, ?)",
        userId,
        "document-repository-it-" + userId,
        "document-repository-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
  }

  @AfterEach
  void tearDown() {
    for (KnowledgeLibrary library : libraries) {
      jdbcTemplate.update("DELETE FROM documents WHERE library_id = ?", library.getId());
      libraryRepository.deleteById(library.getId());
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  @Test
  void theS3BucketsOfALibraryAreTheHostPartsOfItsS3PathsOnceEach() {
    KnowledgeLibrary library = library("Ablage");
    KnowledgeLibrary other = library("Andere Ablage");
    save(library, "s3://dokumente/2025/protokoll.pdf");
    save(library, "s3://dokumente/2026/a/b.pdf");
    save(library, "s3://satzungen.2025/satzung.pdf");
    save(library, "s3://leer/");
    save(library, "uploads/s3://kein-bucket/x.pdf");
    save(other, "s3://fremd/x.pdf");

    assertThat(documentRepository.findS3BucketsByLibraryId(library.getId()))
        .containsExactlyInAnyOrder("dokumente", "satzungen.2025", "leer");
    assertThat(documentRepository.findS3BucketsByLibraryId(UUID.randomUUID())).isEmpty();
  }

  private KnowledgeLibrary library(String name) {
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                name,
                null,
                userId,
                SourceTypes.S3,
                null,
                "https://ablage.example",
                null));
    libraries.add(library);
    return library;
  }

  private void save(KnowledgeLibrary library, String filePath) {
    Document document = new Document(filePath, filePath, "text/plain", 1L, SourceTypes.S3);
    document.setLibraryId(library.getId());
    document.setOrganizationId(Organization.DEFAULT_ID);
    documentRepository.save(document);
  }
}
