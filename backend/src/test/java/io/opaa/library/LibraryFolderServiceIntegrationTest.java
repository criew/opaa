package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.opaa.FakeEmbeddingModel;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;

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
// Own @DynamicPropertySource (below) means Spring's context cache still keys this to its own
// context regardless of the shared @OpaaIntegrationTest base - documented exception per AGENTS.md.
@OpaaIntegrationTest
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

  /** {@link CurrentUser} snapshot for a {@link User} entity this test already loaded/created. */
  private CurrentUser currentUserOf(User user) {
    return currentUserOf(user, false);
  }

  private CurrentUser currentUserOf(User user, boolean systemAdmin) {
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        systemAdmin ? SystemRole.SYSTEM_ADMIN : user.getSystemRole(),
        user.getDisplayName(),
        user.getEmail());
  }

  private CurrentUser currentUserOf(UUID userId) {
    return currentUserOf(userId, false);
  }

  private CurrentUser currentUserOf(UUID userId, boolean systemAdmin) {
    return currentUserOf(userRepository.findById(userId).orElseThrow(), systemAdmin);
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();

    editor = new User("editor-subject", "issuer", "editor@example.com", "Editor");
    editor.setOrganizationId(organizationId);
    editor = userRepository.save(editor);

    viewer = new User("viewer-subject", "issuer", "viewer@example.com", "Viewer");
    viewer.setOrganizationId(organizationId);
    viewer = userRepository.save(viewer);

    var libraryRequest = libraryCreation("Bibliothek", DocumentSourceType.UPLOAD).build();
    var library = libraryService.createLibrary(libraryRequest, currentUserOf(editor));
    libraryId = library.library().getId();

    var grantRequest =
        new AssetGrantUpsert(
            io.opaa.api.types.PermissionSubjectType.USER, viewer.getId(), AssetRole.VIEWER);
    grantService.upsertGrant(libraryId, grantRequest, currentUserOf(editor, false));
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
    LibraryFolderDetail root =
        folderService.createFolder(libraryId, "Protokolle", null, currentUserOf(editor, false));
    assertThat(root.folder().getParentFolderId()).isNull();

    LibraryFolderDetail child =
        folderService.createFolder(
            libraryId, "2026", root.folder().getId(), currentUserOf(editor, false));
    assertThat(child.folder().getParentFolderId()).isEqualTo(root.folder().getId());

    LibraryFolderDetail renamed =
        folderService.renameFolder(
            libraryId, child.folder().getId(), "2026-Q1", currentUserOf(editor, false));
    assertThat(renamed.folder().getName()).isEqualTo("2026-Q1");
    assertThat(folderRepository.findById(child.folder().getId()).orElseThrow().getName())
        .isEqualTo("2026-Q1");
  }

  @Test
  void aDuplicateNameOnTheSameLevelIsRejectedWithConflict() {
    folderService.createFolder(libraryId, "Protokolle", null, currentUserOf(editor, false));

    assertThatThrownBy(
            () ->
                folderService.createFolder(
                    libraryId, "Protokolle", null, currentUserOf(editor, false)))
        .isInstanceOf(ConflictException.class);
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
              LibraryFolderDetail response =
                  folderService.createFolder(
                      libraryId, "Protokolle", null, currentUserOf(editor, false));
              return response.folder().getId();
            } catch (ConflictException e) {
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
    LibraryFolderDetail folderA =
        folderService.createFolder(libraryId, "Ordner A", null, currentUserOf(editor, false));
    LibraryFolderDetail folderB =
        folderService.createFolder(libraryId, "Ordner B", null, currentUserOf(editor, false));

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<UUID> renameA =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              LibraryFolderDetail response =
                  folderService.renameFolder(
                      libraryId, folderA.folder().getId(), "Ziel", currentUserOf(editor, false));
              return response.folder().getId();
            } catch (ConflictException e) {
              return null;
            }
          };
      Callable<UUID> renameB =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              LibraryFolderDetail response =
                  folderService.renameFolder(
                      libraryId, folderB.folder().getId(), "Ziel", currentUserOf(editor, false));
              return response.folder().getId();
            } catch (ConflictException e) {
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
    LibraryFolderDetail parentA =
        folderService.createFolder(libraryId, "Abteilung A", null, currentUserOf(editor, false));
    LibraryFolderDetail parentB =
        folderService.createFolder(libraryId, "Abteilung B", null, currentUserOf(editor, false));

    LibraryFolderDetail childA =
        folderService.createFolder(
            libraryId, "Protokolle", parentA.folder().getId(), currentUserOf(editor, false));
    LibraryFolderDetail childB =
        folderService.createFolder(
            libraryId, "Protokolle", parentB.folder().getId(), currentUserOf(editor, false));

    assertThat(childA.folder().getName()).isEqualTo(childB.folder().getName());
    assertThat(childA.folder().getId()).isNotEqualTo(childB.folder().getId());
  }

  @Test
  void aViewerCannotCreateAFolder() {
    assertThatThrownBy(
            () ->
                folderService.createFolder(
                    libraryId, "Protokolle", null, currentUserOf(viewer, false)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void creatingAFolderInAConnectorLibraryIsRejectedWithConflict() {
    // "/tmp" matches application.yml's default opaa.indexing.filesystem.allowlist ("/data,/tmp") -
    // the source content of this library is never read, only its sourceType matters here.
    var connectorLibraryRequest =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM).sourcePath("/tmp").build();
    var connectorLibrary =
        libraryService.createLibrary(connectorLibraryRequest, currentUserOf(editor));
    try {
      assertThatThrownBy(
              () ->
                  folderService.createFolder(
                      connectorLibrary.library().getId(),
                      "Protokolle",
                      null,
                      currentUserOf(editor, false)))
          .isInstanceOf(ConflictException.class);
    } finally {
      libraryRepository.deleteById(connectorLibrary.library().getId());
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
    LibraryDocumentEntry uploaded =
        documentService.uploadDocument(
            libraryId, textFile(fileName, content), null, currentUserOf(editor));
    Document document = awaitDocumentStatus(uploaded.document().getId(), DocumentStatus.INDEXED);
    document.setFolderId(folderId);
    return documentRepository.save(document);
  }

  @Test
  void deletingAFolderRemovesItsOwnDocumentsAndNestedSubfoldersWithTheirDocuments() {
    LibraryFolderDetail root =
        folderService.createFolder(libraryId, "Archiv", null, currentUserOf(editor, false));
    LibraryFolderDetail child =
        folderService.createFolder(
            libraryId, "2026", root.folder().getId(), currentUserOf(editor, false));

    Document rootDocument =
        uploadDocumentIntoFolder(root.folder().getId(), "root-doc.txt", "Inhalt der Wurzelakte");
    Document childDocument =
        uploadDocumentIntoFolder(child.folder().getId(), "child-doc.txt", "Inhalt der Unterakte");

    Path rootStoredFile = Path.of(rootDocument.getFilePath());
    Path childStoredFile = Path.of(childDocument.getFilePath());
    assertThat(Files.exists(rootStoredFile)).isTrue();
    assertThat(Files.exists(childStoredFile)).isTrue();

    LibraryFolderDetail fetchedBeforeDelete =
        folderService.getFolder(libraryId, root.folder().getId(), currentUserOf(editor, false));
    assertThat(fetchedBeforeDelete.documentCount()).isEqualTo(2L);

    folderService.deleteFolder(libraryId, root.folder().getId(), currentUserOf(editor, false));

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
    assertThat(folderRepository.findById(root.folder().getId())).isEmpty();
    assertThat(folderRepository.findById(child.folder().getId())).isEmpty();
  }

  @Test
  void deletingAnEmptyFolderRemovesOnlyTheFolderRow() {
    LibraryFolderDetail folder =
        folderService.createFolder(libraryId, "Leer", null, currentUserOf(editor, false));

    folderService.deleteFolder(libraryId, folder.folder().getId(), currentUserOf(editor, false));

    assertThat(folderRepository.findById(folder.folder().getId())).isEmpty();
  }

  @Test
  void aViewerCannotDeleteAFolder() {
    LibraryFolderDetail folder =
        folderService.createFolder(libraryId, "Geschützt", null, currentUserOf(editor, false));

    assertThatThrownBy(
            () ->
                folderService.deleteFolder(
                    libraryId, folder.folder().getId(), currentUserOf(viewer, false)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(folderRepository.findById(folder.folder().getId())).isPresent();
  }

  @Test
  void concurrentResolveOrCreateFolderPathCallsForTheSameNewPathProduceExactlyOneFolderTree()
      throws Exception {
    // #823 review, Befund 6: a pre-existing #824 race (materializeSingleFolder's unique-constraint
    // catch retrying its lookup against the same, now-aborted Postgres transaction) made
    // user-reachable by #823 - two concurrent uploads (e.g. two browser tabs) racing to
    // materialize the same brand-new folder path must both succeed and land on the very same
    // folder, never a 500 from the losing side. Only a genuine concurrent attempt (real threads,
    // real Postgres) exercises the unique-index violation this guards against - a sequential call
    // would never hit it.
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<UUID> resolve =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return folderService.resolveOrCreateFolderPath(
                libraryId, null, List.of("Protokolle", "2026"), currentUserOf(editor, false));
          };

      Future<UUID> first = executor.submit(resolve);
      Future<UUID> second = executor.submit(resolve);
      UUID firstResult = first.get(20, TimeUnit.SECONDS);
      UUID secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat(firstResult).isNotNull();
      assertThat(secondResult).isEqualTo(firstResult);
      // Exactly one "Protokolle" and one "2026" row, not one pair per racing call.
      assertThat(folderRepository.findByLibraryId(libraryId)).hasSize(2);
    } finally {
      executor.shutdownNow();
    }
  }
}
