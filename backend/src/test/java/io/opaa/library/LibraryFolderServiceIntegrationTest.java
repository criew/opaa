package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.opaa.FakeEmbeddingModel;
import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.api.dto.LibraryFolderRenameRequest;
import io.opaa.api.dto.LibraryFolderRequest;
import io.opaa.api.dto.LibraryFolderResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.DocumentStatus;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs {@link LibraryFolderService} against a real Postgres database with the real, versioned
 * Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}) - mirrors
 * {@code LibraryDocumentServiceIntegrationTest}, needed here because {@code
 * fk_library_folders_parent}/{@code fk_documents_folder} (migration 062) and the two partial unique
 * indexes {@code uk_library_folders_root_name}/{@code uk_library_folders_child_name} are real,
 * enforced constraints that {@code ddl-auto=create-drop} would not generate from the entity mapping
 * alone (AGENTS.md, "Reproduktionsnachweis"). Exercises the #820 acceptance criteria end to end:
 * nested creation, renaming, a name conflict on the same level, the EDITOR/UPLOAD-only gates, and -
 * the part a mocked {@link LibraryDocumentService} in the unit test cannot prove - that a recursive
 * delete actually removes every contained document's row, vector store chunks and stored file.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class LibraryFolderServiceIntegrationTest {

  @TempDir static Path uploadStorageDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("opaa.upload.storage-path", () -> uploadStorageDir.toAbsolutePath().toString());
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new FakeEmbeddingModel();
    }
  }

  @Autowired private LibraryFolderService folderService;
  @Autowired private LibraryDocumentService documentService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private AssetGrantService grantService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryFolderRepository folderRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;

  private UUID organizationId;
  private User editor;
  private User viewer;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store");
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();

    editor = new User("editor-subject", "issuer", "editor@example.com", "Editor");
    editor.setOrganizationId(organizationId);
    editor = userRepository.save(editor);

    viewer = new User("viewer-subject", "issuer", "viewer@example.com", "Viewer");
    viewer.setOrganizationId(organizationId);
    viewer = userRepository.save(viewer);

    var libraryRequest =
        new io.opaa.api.dto.LibraryRequest("Bibliothek", DocumentSourceType.UPLOAD);
    var library = libraryService.createLibrary(libraryRequest, editor.getId());
    libraryId = library.getId();

    var grantRequest =
        new io.opaa.api.dto.AssetGrantRequest(
            io.opaa.group.PermissionSubjectType.USER, viewer.getId(), AssetRole.VIEWER);
    grantService.upsertGrant(libraryId, grantRequest, editor.getId(), false);
  }

  @AfterEach
  void tearDown() {
    documentRepository.findByLibraryId(libraryId).forEach(documentRepository::delete);
    folderRepository
        .findByLibraryIdAndParentFolderIdOrderByNameAsc(libraryId, null)
        .forEach(this::deleteRecursively);
    libraryRepository.deleteById(libraryId);
    grantHistoryRepository.deleteBySubjectUserIdIn(List.of(editor.getId(), viewer.getId()));
    membershipHistoryRepository.deleteByUserIdIn(List.of(editor.getId(), viewer.getId()));
    userRepository.deleteById(editor.getId());
    userRepository.deleteById(viewer.getId());
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  private void deleteRecursively(LibraryFolder folder) {
    folderRepository
        .findByLibraryIdAndParentFolderIdOrderByNameAsc(libraryId, folder.getId())
        .forEach(this::deleteRecursively);
    folderRepository.delete(folder);
  }

  private MultipartFile textFile(String originalFileName, String content) {
    return new MockMultipartFile("file", originalFileName, "text/plain", content.getBytes());
  }

  private Document awaitDocumentStatus(UUID documentId, DocumentStatus expected) {
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                documentRepository
                    .findById(documentId)
                    .map(Document::getStatus)
                    .filter(status -> status == expected)
                    .isPresent());
    return documentRepository.findById(documentId).orElseThrow();
  }

  @Test
  void editorCreatesRenamesAndNestsFolders() {
    LibraryFolderResponse root =
        folderService.createFolder(
            libraryId, new LibraryFolderRequest("Protokolle"), editor.getId(), false);
    assertThat(root.getParentFolderId()).isNull();

    LibraryFolderResponse child =
        folderService.createFolder(
            libraryId,
            new LibraryFolderRequest("2026").parentFolderId(root.getId()),
            editor.getId(),
            false);
    assertThat(child.getParentFolderId()).isEqualTo(root.getId());

    LibraryFolderResponse renamed =
        folderService.renameFolder(
            libraryId,
            child.getId(),
            new LibraryFolderRenameRequest("2026-Q1"),
            editor.getId(),
            false);
    assertThat(renamed.getName()).isEqualTo("2026-Q1");
    assertThat(folderRepository.findById(child.getId()).orElseThrow().getName())
        .isEqualTo("2026-Q1");
  }

  @Test
  void aDuplicateNameOnTheSameLevelIsRejectedWithConflict() {
    folderService.createFolder(
        libraryId, new LibraryFolderRequest("Protokolle"), editor.getId(), false);

    assertThatThrownBy(
            () ->
                folderService.createFolder(
                    libraryId, new LibraryFolderRequest("Protokolle"), editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void concurrentCreatesOfTheSameFolderNameProduceExactlyOneFolder() throws Exception {
    // Review finding on PR #827: the sequential ensureNameAvailable pre-check alone cannot close
    // this race - only uk_library_folders_root_name (migration 062) can, and only a genuine
    // concurrent attempt (real threads, real Postgres) exercises it rather than the sequential
    // fast-path check the mocked LibraryFolderServiceTest is limited to. Also proves
    // createFolder's saveAndFlush (not a plain save) actually surfaces the unique violation inside
    // the try block as a 409, rather than deferring the INSERT past it.
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<UUID> create =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              LibraryFolderResponse response =
                  folderService.createFolder(
                      libraryId, new LibraryFolderRequest("Protokolle"), editor.getId(), false);
              return response.getId();
            } catch (ResponseStatusException e) {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              return null;
            }
          };

      Future<UUID> first = executor.submit(create);
      Future<UUID> second = executor.submit(create);
      UUID firstResult = first.get(20, TimeUnit.SECONDS);
      UUID secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat((firstResult == null) ^ (secondResult == null))
          .as("Exactly one of the two concurrent creates must succeed")
          .isTrue();
      assertThat(folderRepository.findByLibraryIdAndParentFolderIdOrderByNameAsc(libraryId, null))
          .hasSize(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentRenamesToTheSameNameProduceExactlyOneWinner() throws Exception {
    // Same reasoning as concurrentCreatesOfTheSameFolderNameProduceExactlyOneFolder, for
    // renameFolder's identical saveAndFlush fix: two distinct, pre-existing folders both renamed
    // to the same target name at the same time.
    LibraryFolderResponse folderA =
        folderService.createFolder(
            libraryId, new LibraryFolderRequest("Ordner A"), editor.getId(), false);
    LibraryFolderResponse folderB =
        folderService.createFolder(
            libraryId, new LibraryFolderRequest("Ordner B"), editor.getId(), false);

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<UUID> renameA =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              LibraryFolderResponse response =
                  folderService.renameFolder(
                      libraryId,
                      folderA.getId(),
                      new LibraryFolderRenameRequest("Ziel"),
                      editor.getId(),
                      false);
              return response.getId();
            } catch (ResponseStatusException e) {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              return null;
            }
          };
      Callable<UUID> renameB =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              LibraryFolderResponse response =
                  folderService.renameFolder(
                      libraryId,
                      folderB.getId(),
                      new LibraryFolderRenameRequest("Ziel"),
                      editor.getId(),
                      false);
              return response.getId();
            } catch (ResponseStatusException e) {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              return null;
            }
          };

      Future<UUID> first = executor.submit(renameA);
      Future<UUID> second = executor.submit(renameB);
      UUID firstResult = first.get(20, TimeUnit.SECONDS);
      UUID secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat((firstResult == null) ^ (secondResult == null))
          .as("Exactly one of the two concurrent renames must succeed")
          .isTrue();
      assertThat(folderRepository.findByLibraryIdAndParentFolderIdIsNullAndName(libraryId, "Ziel"))
          .isPresent();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void theSameNameIsAllowedUnderDifferentParents() {
    LibraryFolderResponse parentA =
        folderService.createFolder(
            libraryId, new LibraryFolderRequest("Abteilung A"), editor.getId(), false);
    LibraryFolderResponse parentB =
        folderService.createFolder(
            libraryId, new LibraryFolderRequest("Abteilung B"), editor.getId(), false);

    LibraryFolderResponse childA =
        folderService.createFolder(
            libraryId,
            new LibraryFolderRequest("Protokolle").parentFolderId(parentA.getId()),
            editor.getId(),
            false);
    LibraryFolderResponse childB =
        folderService.createFolder(
            libraryId,
            new LibraryFolderRequest("Protokolle").parentFolderId(parentB.getId()),
            editor.getId(),
            false);

    assertThat(childA.getName()).isEqualTo(childB.getName());
    assertThat(childA.getId()).isNotEqualTo(childB.getId());
  }

  @Test
  void aViewerCannotCreateAFolder() {
    assertThatThrownBy(
            () ->
                folderService.createFolder(
                    libraryId, new LibraryFolderRequest("Protokolle"), viewer.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void creatingAFolderInAConnectorLibraryIsRejectedWithConflict() {
    // "/tmp" matches application.yml's default opaa.indexing.filesystem-allowlist ("/data,/tmp") -
    // the source content of this library is never read, only its sourceType matters here.
    var connectorLibraryRequest =
        new io.opaa.api.dto.LibraryRequest("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/tmp");
    var connectorLibrary = libraryService.createLibrary(connectorLibraryRequest, editor.getId());
    try {
      assertThatThrownBy(
              () ->
                  folderService.createFolder(
                      connectorLibrary.getId(),
                      new LibraryFolderRequest("Protokolle"),
                      editor.getId(),
                      false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex ->
                  assertThat(((ResponseStatusException) ex).getStatusCode())
                      .isEqualTo(HttpStatus.CONFLICT));
    } finally {
      libraryRepository.deleteById(connectorLibrary.getId());
    }
  }

  /**
   * Uploads a document normally (through {@link LibraryDocumentService}, which does not yet assign
   * a {@code folderId} - that lands in #821) and then places it into {@code folderId} directly,
   * standing in for the folder-aware upload path #821 will add. Sufficient here: this class tests
   * {@link LibraryFolderService#deleteFolder}'s cleanup of documents already sitting in a folder,
   * not how they got there.
   */
  private Document uploadDocumentIntoFolder(UUID folderId, String fileName, String content) {
    LibraryDocumentResponse uploaded =
        documentService.uploadDocument(
            libraryId, textFile(fileName, content), null, editor.getId(), false);
    Document document = awaitDocumentStatus(uploaded.getId(), DocumentStatus.INDEXED);
    document.setFolderId(folderId);
    return documentRepository.save(document);
  }

  @Test
  void deletingAFolderRemovesItsOwnDocumentsAndNestedSubfoldersWithTheirDocuments() {
    LibraryFolderResponse root =
        folderService.createFolder(
            libraryId, new LibraryFolderRequest("Archiv"), editor.getId(), false);
    LibraryFolderResponse child =
        folderService.createFolder(
            libraryId,
            new LibraryFolderRequest("2026").parentFolderId(root.getId()),
            editor.getId(),
            false);

    Document rootDocument =
        uploadDocumentIntoFolder(root.getId(), "root-doc.txt", "Inhalt der Wurzelakte");
    Document childDocument =
        uploadDocumentIntoFolder(child.getId(), "child-doc.txt", "Inhalt der Unterakte");

    Path rootStoredFile = Path.of(rootDocument.getFilePath());
    Path childStoredFile = Path.of(childDocument.getFilePath());
    assertThat(Files.exists(rootStoredFile)).isTrue();
    assertThat(Files.exists(childStoredFile)).isTrue();

    LibraryFolderResponse fetchedBeforeDelete =
        folderService.getFolder(libraryId, root.getId(), editor.getId(), false);
    assertThat(fetchedBeforeDelete.getDocumentCount()).isEqualTo(2L);

    folderService.deleteFolder(libraryId, root.getId(), editor.getId(), false);

    // Both documents are gone - row, stored file, and vector store chunks (ADR-0020,
    // Entscheidung 5: the recursive delete runs through LibraryDocumentService#deleteDocument,
    // never a database cascade).
    assertThat(documentRepository.findById(rootDocument.getId())).isEmpty();
    assertThat(documentRepository.findById(childDocument.getId())).isEmpty();
    assertThat(Files.exists(rootStoredFile)).isFalse();
    assertThat(Files.exists(childStoredFile)).isFalse();
    Long remainingChunks =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' IN (?, ?)",
            Long.class,
            rootDocument.getId().toString(),
            childDocument.getId().toString());
    assertThat(remainingChunks).isZero();

    // Both folder rows are gone too.
    assertThat(folderRepository.findById(root.getId())).isEmpty();
    assertThat(folderRepository.findById(child.getId())).isEmpty();
  }

  @Test
  void deletingAnEmptyFolderRemovesOnlyTheFolderRow() {
    LibraryFolderResponse folder =
        folderService.createFolder(
            libraryId, new LibraryFolderRequest("Leer"), editor.getId(), false);

    folderService.deleteFolder(libraryId, folder.getId(), editor.getId(), false);

    assertThat(folderRepository.findById(folder.getId())).isEmpty();
  }

  @Test
  void aViewerCannotDeleteAFolder() {
    LibraryFolderResponse folder =
        folderService.createFolder(
            libraryId, new LibraryFolderRequest("Geschützt"), editor.getId(), false);

    assertThatThrownBy(
            () -> folderService.deleteFolder(libraryId, folder.getId(), viewer.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
    assertThat(folderRepository.findById(folder.getId())).isPresent();
  }
}
