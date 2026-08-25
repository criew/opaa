package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Reproduces the deletion-window bug #632 fixes against the real Liquibase schema, not just {@link
 * FileProcessingServiceTest}'s mocked {@link DocumentRepository}: a connector document is deleted
 * (e.g. by {@code LibraryDocumentService#deleteDocument}, or a whole connector library being
 * removed) after {@link FileProcessingService#processFile}/{@code #processUrlFile}/{@code
 * #processRssEntry} have already inserted the row and started parsing, but before the final status
 * transition runs. Before the fix, that transition was a plain {@code documentRepository.save(doc)}
 * on a detached, already-deleted entity - {@link Document} assigns its own id and carries no
 * {@code @Version}, so Hibernate silently re-{@code INSERT}s it as a zombie row, and any chunks
 * {@code storeChunks} already wrote survive as orphans.
 *
 * <p>{@link DocumentService} is mocked here, not the repository/vector store: the delete is
 * triggered as a side effect of {@code parseDocument}, the same point in real time a concurrent
 * request would land - genuinely racing the row's existence, not simulating a zero-rows-updated
 * result the way {@link FileProcessingServiceTest} does against a mocked repository.
 */
// Own @MockitoBean DocumentService below (needed to force the race window this class reproduces -
// see the class Javadoc) means Spring's context cache still keys this to its own context
// regardless of the shared @OpaaIndexingIntegrationTest base - documented exception per AGENTS.md.
@OpaaIndexingIntegrationTest
class FileProcessingServiceIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("file-processing-service");

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @MockitoBean private DocumentService documentService;

  private UUID userId;
  private KnowledgeLibrary targetLibrary;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    documentRepository.deleteAll();

    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'file-processing-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'file-processing-it@example.com'");
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'file-processing-it@example.com',"
            + " 'File Processing IT User', now(), ?, ?)",
        userId,
        "file-processing-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);

    targetLibrary =
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
  void filesystemDocumentDeletedWhileBeingIndexedLeavesNoZombieRowOrOrphanedChunks()
      throws IOException {
    Path file = classTempDir.resolve("race.txt");
    Files.writeString(file, "content that outlives its own document row");

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file))
        .thenAnswer(
            inv -> {
              // Simulates a concurrent LibraryDocumentService#deleteDocument (or a connector
              // library delete) landing right after processFile's own initial insert, before the
              // status transition below ever runs - the exact window #632 closes.
              documentRepository.deleteAll();
              return parsed;
            });

    FileProcessingResult result = fileProcessingService.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.SKIPPED);
    assertThat(documentRepository.count())
        .as("the deleted row must not be re-inserted as a zombie")
        .isZero();
    Long chunkCount = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
    assertThat(chunkCount)
        .as("chunks written for the now-deleted document must not survive as orphans")
        .isZero();
  }

  @Test
  void filesystemDocumentStillPresentIsIndexedNormally() throws IOException {
    // Control case: without the concurrent delete, the same file is indexed and left INDEXED -
    // proves the assertions above actually distinguish the race from ordinary success.
    Path file = classTempDir.resolve("normal.txt");
    Files.writeString(file, "content that is never deleted");

    var parsed = List.of(new org.springframework.ai.document.Document("parsed text"));
    when(documentService.parseDocument(file)).thenReturn(parsed);

    FileProcessingResult result = fileProcessingService.processFile(file, targetLibrary);

    assertThat(result).isEqualTo(FileProcessingResult.PROCESSED);
    assertThat(documentRepository.count()).isEqualTo(1);
    Document doc = documentRepository.findAll().getFirst();
    assertThat(doc.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(doc.getChecksum()).isNotNull();
    Long chunkCount = jdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
    assertThat(chunkCount).isPositive();
  }
}
