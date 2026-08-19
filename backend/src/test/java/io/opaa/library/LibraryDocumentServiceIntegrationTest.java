package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.FakeEmbeddingModel;
import io.opaa.TestcontainersConfiguration;
import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.DocumentStatus;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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
 * Runs {@link LibraryDocumentService} against a real Postgres database with the real, versioned
 * Liquibase schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}) - the
 * pattern {@code KnowledgeLibraryServiceIntegrationTest} and {@code
 * DocumentIndexingIntegrationTest} already establish, needed here because {@code
 * fk_documents_uploaded_by_user} (migration 020) and {@code chk_documents_source_type}'s widened
 * check are real, enforced constraints, not something {@code ddl-auto=create-drop} would generate
 * from the entity mapping alone (AGENTS.md, "Reproduktionsnachweis"). Exercises the acceptance
 * criteria end to end: upload, then find the content through the vector store the query endpoint
 * reads from; a VIEWER is refused; an unsupported format and an over-limit file are refused without
 * a stored file; a duplicate checksum in the same library is refused, the same content in a
 * different library is not; deleting removes the row, the chunks and the file.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class LibraryDocumentServiceIntegrationTest {

  @TempDir static Path uploadStorageDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("opaa.upload.storage-path", () -> uploadStorageDir.toAbsolutePath().toString());
    registry.add("opaa.upload.max-file-size", () -> 1024);
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    EmbeddingModel testEmbeddingModel() {
      return new FakeEmbeddingModel();
    }
  }

  @Autowired private LibraryDocumentService documentService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private AssetGrantService grantService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private VectorStore vectorStore;
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
    documentRepository.deleteAll();
    libraryRepository.deleteById(libraryId);
    // #238 code review, finding 2+4: asset_grant_history.subject_user_id is ON DELETE RESTRICT
    // (see 018-permission-history.yaml's "Deletion survival" comment) - every library/grant
    // operation setUp performs now historises a row referencing editor/viewer, which must be
    // purged before this teardown's own user deletion below (not a real account deletion).
    grantHistoryRepository.deleteBySubjectUserIdIn(List.of(editor.getId(), viewer.getId()));
    membershipHistoryRepository.deleteByUserIdIn(List.of(editor.getId(), viewer.getId()));
    userRepository.deleteById(editor.getId());
    userRepository.deleteById(viewer.getId());
    // #392: setUp's library/grant creation now also writes audit_log rows
    // (fk_audit_log_organization is ON DELETE RESTRICT, migration 017).
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  private MultipartFile textFile(String originalFileName, String content) {
    return new MockMultipartFile("file", originalFileName, "text/plain", content.getBytes());
  }

  @Test
  void editorUploadsAndTheContentIsFindableThroughTheVectorStore() {
    LibraryDocumentResponse response =
        documentService.uploadDocument(
            libraryId,
            textFile("dienstanweisung.txt", "Diese Dienstanweisung regelt den Publikumsverkehr."),
            editor.getId(),
            false);

    assertThat(response.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(response.getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
    assertThat(response.getUploadedByUserId()).isEqualTo(editor.getId());

    Document saved = documentRepository.findById(response.getId()).orElseThrow();
    assertThat(saved.getLibraryId()).isEqualTo(libraryId);
    assertThat(saved.getOrganizationId()).isEqualTo(organizationId);

    List<org.springframework.ai.document.Document> results =
        vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("Publikumsverkehr")
                .topK(100)
                .similarityThreshold(0.0)
                .build());
    assertThat(results).isNotEmpty();
    assertThat(results)
        .anyMatch(r -> libraryId.toString().equals(r.getMetadata().get("library_id")));
    assertThat(results)
        .anyMatch(r -> response.getId().toString().equals(r.getMetadata().get("document_id")));
  }

  @Test
  void aViewerCannotUpload() {
    assertThatThrownBy(
            () ->
                documentService.uploadDocument(
                    libraryId, textFile("x.txt", "content"), viewer.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
    assertThat(documentRepository.findByLibraryId(libraryId)).isEmpty();
  }

  @Test
  void anUnsupportedFormatIsRejectedWithoutStoringAFile() throws IOException {
    MultipartFile unsupported =
        new MockMultipartFile("file", "malware.exe", "application/octet-stream", "x".getBytes());

    assertThatThrownBy(
            () -> documentService.uploadDocument(libraryId, unsupported, editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
    assertThat(documentRepository.findByLibraryId(libraryId)).isEmpty();
    assertNoFilesStored();
  }

  @Test
  void aFileOverTheConfiguredLimitIsRejectedWithoutStoringAFile() throws IOException {
    MultipartFile tooBig = textFile("big.txt", "x".repeat(2000));

    assertThatThrownBy(
            () -> documentService.uploadDocument(libraryId, tooBig, editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
    assertThat(documentRepository.findByLibraryId(libraryId)).isEmpty();
    assertNoFilesStored();
  }

  @Test
  void theSameFileTwiceInTheSameLibraryIsRejectedButADifferentLibraryIsAllowed() {
    String content = "identical content";
    documentService.uploadDocument(
        libraryId, textFile("first.txt", content), editor.getId(), false);

    assertThatThrownBy(
            () ->
                documentService.uploadDocument(
                    libraryId, textFile("second.txt", content), editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(documentRepository.findByLibraryId(libraryId)).hasSize(1);

    var secondLibraryRequest =
        new io.opaa.api.dto.LibraryRequest("Zweite Bibliothek", DocumentSourceType.UPLOAD);
    var secondLibrary = libraryService.createLibrary(secondLibraryRequest, editor.getId());
    try {
      LibraryDocumentResponse response =
          documentService.uploadDocument(
              secondLibrary.getId(), textFile("third.txt", content), editor.getId(), false);
      assertThat(response.getStatus()).isEqualTo(DocumentStatus.INDEXED);
      assertThat(documentRepository.findByLibraryId(secondLibrary.getId())).hasSize(1);
    } finally {
      documentRepository.findByLibraryId(secondLibrary.getId()).forEach(documentRepository::delete);
      libraryRepository.deleteById(secondLibrary.getId());
    }
  }

  @Test
  void deletingRemovesTheRowTheChunksAndTheFile() {
    LibraryDocumentResponse uploaded =
        documentService.uploadDocument(
            libraryId, textFile("to-delete.txt", "content to remove"), editor.getId(), false);
    Document savedDoc = documentRepository.findById(uploaded.getId()).orElseThrow();
    Path storedFile = Path.of(savedDoc.getFilePath());
    assertThat(Files.exists(storedFile)).isTrue();

    Long chunksBefore =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?",
            Long.class,
            uploaded.getId().toString());
    assertThat(chunksBefore).isGreaterThan(0);

    documentService.deleteDocument(libraryId, uploaded.getId(), editor.getId(), false);

    assertThat(documentRepository.findById(uploaded.getId())).isEmpty();
    assertThat(Files.exists(storedFile)).isFalse();
    Long chunksAfter =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?",
            Long.class,
            uploaded.getId().toString());
    assertThat(chunksAfter).isZero();
  }

  @Test
  void aViewerCannotDelete() {
    LibraryDocumentResponse uploaded =
        documentService.uploadDocument(
            libraryId, textFile("protected.txt", "content"), editor.getId(), false);

    assertThatThrownBy(
            () ->
                documentService.deleteDocument(libraryId, uploaded.getId(), viewer.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
    assertThat(documentRepository.findById(uploaded.getId())).isPresent();
  }

  @Test
  void aUserWithNoGrantAtAllGets404NotForbidden() {
    User stranger = new User("stranger-subject", "issuer", "stranger@example.com", "Stranger");
    stranger.setOrganizationId(organizationId);
    stranger = userRepository.save(stranger);

    try {
      var strangerId = stranger.getId();
      assertThatThrownBy(
              () ->
                  documentService.uploadDocument(
                      libraryId, textFile("x.txt", "content"), strangerId, false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex ->
                  assertThat(((ResponseStatusException) ex).getStatusCode())
                      .isEqualTo(HttpStatus.NOT_FOUND));
    } finally {
      userRepository.deleteById(stranger.getId());
    }
  }

  @Test
  void deletingAFilesystemSourcedDocumentRemovesTheRowButNeverItsSourceFile(@TempDir Path crawlDir)
      throws IOException {
    // #420 code review, finding 1 (blocking): an EDITOR on this library must be able to remove a
    // FILESYSTEM-sourced document's row and chunks like any other, but the file itself lives in
    // the operator-managed indexing directory - deleteDocument must never touch it, even though
    // nothing today reserves the library's grants to upload-only content.
    Path crawledFile = crawlDir.resolve("dienstanweisung.txt");
    Files.writeString(crawledFile, "Original vom Betrieb verwaltete Datei.");

    Document crawlDoc =
        new Document(
            "dienstanweisung.txt",
            crawledFile.toString(),
            "text/plain",
            Files.size(crawledFile),
            DocumentSourceType.FILESYSTEM);
    crawlDoc.setLibraryId(libraryId);
    crawlDoc.setOrganizationId(organizationId);
    crawlDoc = documentRepository.save(crawlDoc);

    documentService.deleteDocument(libraryId, crawlDoc.getId(), editor.getId(), false);

    assertThat(documentRepository.findById(crawlDoc.getId())).isEmpty();
    assertThat(Files.exists(crawledFile))
        .as("A FILESYSTEM document's source file must survive deleteDocument")
        .isTrue();
  }

  @Test
  void concurrentUploadsOfTheSameFileIntoTheSameLibraryProduceExactlyOneDocument()
      throws Exception {
    // #420 code review, nit 5: the sequential findByLibraryIdAndChecksum check alone cannot close
    // this race - only uk_documents_library_checksum (migration 020) can, and only a genuine
    // concurrent attempt (real threads, real Postgres) actually exercises it rather than the
    // sequential fast-path check.
    String identicalContent = "identical concurrent content";
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      // Returns the winner's document id, or null for the loser - #420 second code review round,
      // nit 1: a plain boolean cannot check the vector store afterwards, and that omission is
      // exactly what let the loser's orphaned chunks slip through the first version of this test.
      Callable<UUID> upload =
          () -> {
            barrier.await(10, TimeUnit.SECONDS);
            try {
              LibraryDocumentResponse response =
                  documentService.uploadDocument(
                      libraryId,
                      textFile(
                          "racer-" + Thread.currentThread().getId() + ".txt", identicalContent),
                      editor.getId(),
                      false);
              return response.getId();
            } catch (ResponseStatusException e) {
              assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              return null;
            }
          };

      Future<UUID> first = executor.submit(upload);
      Future<UUID> second = executor.submit(upload);
      UUID firstResult = first.get(20, TimeUnit.SECONDS);
      UUID secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat((firstResult == null) ^ (secondResult == null))
          .as("Exactly one of the two concurrent uploads must succeed")
          .isTrue();
      UUID winnerId = firstResult != null ? firstResult : secondResult;
      assertThat(documentRepository.findByLibraryId(libraryId)).hasSize(1);

      // The actual regression this test exists for (#420 second code review round, finding 1): the
      // loser must not have written chunks to the vector store before losing the race - the
      // checksum has to be set before chunking/embedding, not after, for that to hold.
      Long chunksNotBelongingToTheWinner =
          jdbcTemplate.queryForObject(
              "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' <> ?",
              Long.class,
              winnerId.toString());
      assertThat(chunksNotBelongingToTheWinner)
          .as("The losing upload must leave no orphaned chunks in the vector store")
          .isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void uploadingIntoAConnectorLibraryIsRejectedWithConflict() {
    // #479, ADR-0018 Entscheidung 1: only a UPLOAD library accepts manually uploaded files.
    var connectorLibraryRequest =
        new io.opaa.api.dto.LibraryRequest("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents");
    var connectorLibrary = libraryService.createLibrary(connectorLibraryRequest, editor.getId());
    try {
      assertThatThrownBy(
              () ->
                  documentService.uploadDocument(
                      connectorLibrary.getId(),
                      textFile("x.txt", "content"),
                      editor.getId(),
                      false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex ->
                  assertThat(((ResponseStatusException) ex).getStatusCode())
                      .isEqualTo(HttpStatus.CONFLICT));
      assertThat(documentRepository.findByLibraryId(connectorLibrary.getId())).isEmpty();
    } finally {
      libraryRepository.deleteById(connectorLibrary.getId());
    }
  }

  @Test
  void deletingAConnectorLibraryRemovesItsDocumentsAndVectorStoreChunks() {
    // #479, ADR-0018 Entscheidung 5: a lauf-basierte (connector) library's delete takes its whole
    // bestand with it - document rows and vector store chunks - rather than being blocked, unlike
    // UPLOAD (see cannotDeleteALibraryThatStillContainsDocuments's UPLOAD-only counterpart in
    // KnowledgeLibraryServiceIntegrationTest).
    var connectorLibraryRequest =
        new io.opaa.api.dto.LibraryRequest("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents");
    var connectorLibrary = libraryService.createLibrary(connectorLibraryRequest, editor.getId());

    Document crawlDoc =
        new Document(
            "dienstanweisung.txt",
            "/data/documents/dienstanweisung.txt",
            "text/plain",
            10L,
            DocumentSourceType.FILESYSTEM);
    crawlDoc.setLibraryId(connectorLibrary.getId());
    crawlDoc.setOrganizationId(organizationId);
    crawlDoc = documentRepository.save(crawlDoc);

    vectorStore.add(
        List.of(
            new org.springframework.ai.document.Document(
                "Diese Dienstanweisung regelt den Publikumsverkehr.",
                Map.of(
                    "document_id", crawlDoc.getId().toString(),
                    "chunk_index", 0,
                    "file_name", crawlDoc.getFileName(),
                    "library_id", connectorLibrary.getId().toString(),
                    "organization_id", organizationId.toString()))));

    Long chunksBefore =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            connectorLibrary.getId().toString());
    assertThat(chunksBefore).isEqualTo(1);

    libraryService.deleteLibrary(connectorLibrary.getId(), editor.getId(), false);

    assertThat(libraryRepository.findById(connectorLibrary.getId())).isEmpty();
    assertThat(documentRepository.findById(crawlDoc.getId())).isEmpty();
    Long chunksAfter =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            connectorLibrary.getId().toString());
    assertThat(chunksAfter).isZero();
  }

  @Test
  void aPathTraversingFileNameStaysInsideTheLibraryStorageDirectory() {
    LibraryDocumentResponse response =
        documentService.uploadDocument(
            libraryId,
            textFile("../../../../etc/evil.txt", "traversal content"),
            editor.getId(),
            false);

    Document saved = documentRepository.findById(response.getId()).orElseThrow();
    Path storedFile = Path.of(saved.getFilePath()).toAbsolutePath().normalize();
    Path libraryDir = uploadStorageDir.resolve(libraryId.toString()).toAbsolutePath().normalize();
    assertThat(storedFile.startsWith(libraryDir)).isTrue();
    assertThat(saved.getFileName()).isEqualTo("evil.txt");
  }

  private void assertNoFilesStored() throws IOException {
    Path libraryDir = uploadStorageDir.resolve(libraryId.toString());
    if (!Files.exists(libraryDir)) {
      return;
    }
    try (var walk = Files.walk(libraryDir)) {
      assertThat(walk.filter(Files::isRegularFile)).isEmpty();
    }
  }
}
