package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.opaa.FakeEmbeddingModel;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.DocumentStatus;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

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
 *
 * <p>{@code uploadDocument} itself only ever returns {@code PENDING} now (#434) - parsing and
 * embedding run asynchronously on {@code uploadTaskExecutor}, the real thread pool this test's
 * Spring context wires up (unlike the unit tests in {@code LibraryDocumentServiceTest}, which mock
 * {@code FileProcessingService} outright). {@link #awaitDocumentStatus} polls the row via
 * Awaitility the same way {@code DocumentIndexingIntegrationTest#awaitJobCompletion} already does
 * for a directory/URL indexing run, wherever a test needs the eventual {@code INDEXED}/{@code
 * FAILED} outcome rather than the immediate {@code PENDING} response.
 */
// Own @DynamicPropertySource (below) means Spring's context cache still keys this to its own
// context regardless of the shared @OpaaIntegrationTest base - documented exception per AGENTS.md.
@OpaaIntegrationTest
class LibraryDocumentServiceIntegrationTest {

  @TempDir static Path uploadStorageDir;

  // #742 review, finding 3: a base directory the FILESYSTEM loadContent tests below can use as a
  // library's sourcePath, alongside the shared suite's fixed "/data,/tmp" default (see
  // application.yml's comment on filesystem-allowlist) rather than replacing it - the existing
  // FILESYSTEM-flavoured tests elsewhere in this class (e.g.
  // uploadingIntoAConnectorLibraryIsRejectedWithConflict) still rely on "/data/documents" resolving
  // under that default.
  @TempDir static Path filesystemAllowlistDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("opaa.upload.storage-path", () -> uploadStorageDir.toAbsolutePath().toString());
    registry.add("opaa.upload.max-file-size", () -> 1024);
    registry.add(
        "opaa.indexing.filesystem-allowlist",
        () -> "/data,/tmp," + filesystemAllowlistDir.toAbsolutePath());
    // #747: target validation stays enabled (application.yml's own default) - only 127.0.0.1 is
    // allowlisted, so this suite's own local HttpServer instances are reachable for the remote
    // content proxy tests without weakening the check for anything else (mirrors
    // BoundedDownloaderTest#downloadRejectsARedirectToABlockedTargetWhenValidationIsEnabled's
    // identical, narrowly scoped allowlist).
    registry.add("opaa.indexing.target-validation.allowlist", () -> "127.0.0.1");
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
  @Autowired private LibraryFolderRepository folderRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private VectorStore vectorStore;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private AssetGrantRepository assetGrantRepository;

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

    var libraryRequest = libraryCreation("Bibliothek", DocumentSourceType.UPLOAD).build();
    var library = libraryService.createLibrary(libraryRequest, editor.getId());
    libraryId = library.library().getId();

    var grantRequest =
        new AssetGrantUpsert(
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
    LibraryDocumentEntry response =
        documentService.uploadDocument(
            libraryId,
            textFile("dienstanweisung.txt", "Diese Dienstanweisung regelt den Publikumsverkehr."),
            null,
            editor.getId(),
            false);

    // #434: uploadDocument itself only ever returns PENDING - parsing/embedding still run on
    // uploadTaskExecutor after this call has already returned.
    assertThat(response.document().getStatus()).isEqualTo(DocumentStatus.PENDING);
    assertThat(response.document().getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
    assertThat(response.document().getUploadedByUserId()).isEqualTo(editor.getId());

    Document saved = awaitDocumentStatus(response.document().getId(), DocumentStatus.INDEXED);
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
        .anyMatch(
            r -> response.document().getId().toString().equals(r.getMetadata().get("document_id")));
  }

  @Test
  void aViewerCannotUpload() {
    assertThatThrownBy(
            () ->
                documentService.uploadDocument(
                    libraryId, textFile("x.txt", "content"), null, viewer.getId(), false))
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
            () ->
                documentService.uploadDocument(libraryId, unsupported, null, editor.getId(), false))
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
            () -> documentService.uploadDocument(libraryId, tooBig, null, editor.getId(), false))
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
        libraryId, textFile("first.txt", content), null, editor.getId(), false);

    assertThatThrownBy(
            () ->
                documentService.uploadDocument(
                    libraryId, textFile("second.txt", content), null, editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
    assertThat(documentRepository.findByLibraryId(libraryId)).hasSize(1);

    var secondLibraryRequest =
        libraryCreation("Zweite Bibliothek", DocumentSourceType.UPLOAD).build();
    var secondLibrary = libraryService.createLibrary(secondLibraryRequest, editor.getId());
    try {
      LibraryDocumentEntry response =
          documentService.uploadDocument(
              secondLibrary.library().getId(),
              textFile("third.txt", content),
              null,
              editor.getId(),
              false);
      awaitDocumentStatus(response.document().getId(), DocumentStatus.INDEXED);
      assertThat(documentRepository.findByLibraryId(secondLibrary.library().getId())).hasSize(1);
    } finally {
      documentRepository
          .findByLibraryId(secondLibrary.library().getId())
          .forEach(documentRepository::delete);
      libraryRepository.deleteById(secondLibrary.library().getId());
    }
  }

  @Test
  void deletingRemovesTheRowTheChunksAndTheFile() {
    LibraryDocumentEntry uploaded =
        documentService.uploadDocument(
            libraryId, textFile("to-delete.txt", "content to remove"), null, editor.getId(), false);
    Document savedDoc = awaitDocumentStatus(uploaded.document().getId(), DocumentStatus.INDEXED);
    Path storedFile = Path.of(savedDoc.getFilePath());
    assertThat(Files.exists(storedFile)).isTrue();

    Long chunksBefore =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?",
            Long.class,
            uploaded.document().getId().toString());
    assertThat(chunksBefore).isGreaterThan(0);

    documentService.deleteDocument(libraryId, uploaded.document().getId(), editor.getId(), false);

    assertThat(documentRepository.findById(uploaded.document().getId())).isEmpty();
    assertThat(Files.exists(storedFile)).isFalse();
    Long chunksAfter =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'document_id' = ?",
            Long.class,
            uploaded.document().getId().toString());
    assertThat(chunksAfter).isZero();
  }

  @Test
  void aViewerCannotDelete() {
    LibraryDocumentEntry uploaded =
        documentService.uploadDocument(
            libraryId, textFile("protected.txt", "content"), null, editor.getId(), false);

    assertThatThrownBy(
            () ->
                documentService.deleteDocument(
                    libraryId, uploaded.document().getId(), viewer.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN));
    assertThat(documentRepository.findById(uploaded.document().getId())).isPresent();
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
                      libraryId, textFile("x.txt", "content"), null, strangerId, false))
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
              LibraryDocumentEntry response =
                  documentService.uploadDocument(
                      libraryId,
                      textFile(
                          "racer-" + Thread.currentThread().getId() + ".txt", identicalContent),
                      null,
                      editor.getId(),
                      false);
              return response.document().getId();
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
      // #434: the winner's chunks are written asynchronously - wait for that to finish before
      // checking the vector store below, or this could observe it mid-write.
      awaitDocumentStatus(winnerId, DocumentStatus.INDEXED);

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
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents")
            .build();
    var connectorLibrary = libraryService.createLibrary(connectorLibraryRequest, editor.getId());
    try {
      assertThatThrownBy(
              () ->
                  documentService.uploadDocument(
                      connectorLibrary.library().getId(),
                      textFile("x.txt", "content"),
                      null,
                      editor.getId(),
                      false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex ->
                  assertThat(((ResponseStatusException) ex).getStatusCode())
                      .isEqualTo(HttpStatus.CONFLICT));
      assertThat(documentRepository.findByLibraryId(connectorLibrary.library().getId())).isEmpty();
    } finally {
      libraryRepository.deleteById(connectorLibrary.library().getId());
    }
  }

  @Test
  void deletingAConnectorLibraryRemovesItsDocumentsAndVectorStoreChunks() {
    // #479, ADR-0018 Entscheidung 5: a lauf-basierte (connector) library's delete takes its whole
    // bestand with it - document rows and vector store chunks - rather than being blocked, unlike
    // UPLOAD (see cannotDeleteALibraryThatStillContainsDocuments's UPLOAD-only counterpart in
    // KnowledgeLibraryServiceIntegrationTest).
    var connectorLibraryRequest =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents")
            .build();
    var connectorLibrary = libraryService.createLibrary(connectorLibraryRequest, editor.getId());

    Document crawlDoc =
        new Document(
            "dienstanweisung.txt",
            "/data/documents/dienstanweisung.txt",
            "text/plain",
            10L,
            DocumentSourceType.FILESYSTEM);
    crawlDoc.setLibraryId(connectorLibrary.library().getId());
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
                    "library_id", connectorLibrary.library().getId().toString(),
                    "organization_id", organizationId.toString()))));

    Long chunksBefore =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            connectorLibrary.library().getId().toString());
    assertThat(chunksBefore).isEqualTo(1);

    libraryService.deleteLibrary(connectorLibrary.library().getId(), editor.getId(), false);

    assertThat(libraryRepository.findById(connectorLibrary.library().getId())).isEmpty();
    assertThat(documentRepository.findById(crawlDoc.getId())).isEmpty();
    Long chunksAfter =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM vector_store WHERE metadata->>'library_id' = ?",
            Long.class,
            connectorLibrary.library().getId().toString());
    assertThat(chunksAfter).isZero();

    // #479 review nit: documentsRemoved in the LIBRARY_DELETED audit entry must come from
    // DocumentRepository#deleteByLibraryId's own bulk-delete row count, not from a count taken
    // before the delete - see KnowledgeLibraryService#deleteLibrary.
    String deletionPayload =
        jdbcTemplate.queryForObject(
            "SELECT before FROM audit_log WHERE object_id = ? AND event_type = 'LIBRARY_DELETED'",
            String.class,
            connectorLibrary.library().getId().toString());
    assertThat(deletionPayload).contains("\"documentsRemoved\":1");
  }

  @Test
  void aPathTraversingFileNameStaysInsideTheLibraryStorageDirectory() {
    LibraryDocumentEntry response =
        documentService.uploadDocument(
            libraryId,
            textFile("../../../../etc/evil.txt", "traversal content"),
            null,
            editor.getId(),
            false);

    Document saved = documentRepository.findById(response.document().getId()).orElseThrow();
    Path storedFile = Path.of(saved.getFilePath()).toAbsolutePath().normalize();
    Path libraryDir = uploadStorageDir.resolve(libraryId.toString()).toAbsolutePath().normalize();
    assertThat(storedFile.startsWith(libraryDir)).isTrue();
    assertThat(saved.getFileName()).isEqualTo("evil.txt");
  }

  // #736: GET /api/v1/documents/{documentId}/content, backed by LibraryDocumentService#loadContent.

  @Test
  void loadContentReturnsTheStoredFileForAnUploadedDocumentToAnyoneWithAtLeastViewer() {
    LibraryDocumentEntry uploaded =
        documentService.uploadDocument(
            libraryId,
            textFile("bescheid.txt", "Originaltext des Bescheids."),
            null,
            editor.getId(),
            false);
    Document saved = awaitDocumentStatus(uploaded.document().getId(), DocumentStatus.INDEXED);

    DocumentContent contentForEditor =
        documentService.loadContent(uploaded.document().getId(), editor.getId(), false);
    assertThat(contentForEditor.path()).isEqualTo(Path.of(saved.getFilePath()));
    assertThat(contentForEditor.fileName()).isEqualTo("bescheid.txt");

    // VIEWER is the acceptance criteria's floor - the same role listing/reading the library
    // configuration already requires, one below the EDITOR upload/delete needs.
    DocumentContent contentForViewer =
        documentService.loadContent(uploaded.document().getId(), viewer.getId(), false);
    assertThat(contentForViewer.path()).isEqualTo(Path.of(saved.getFilePath()));
  }

  @Test
  void loadContentRefusesAUserWithNoGrantAtAllWith404NotForbidden() {
    LibraryDocumentEntry uploaded =
        documentService.uploadDocument(
            libraryId, textFile("geheim.txt", "content"), null, editor.getId(), false);
    awaitDocumentStatus(uploaded.document().getId(), DocumentStatus.INDEXED);

    User stranger = new User("content-stranger-subject", "issuer", "stranger2@example.com", "S");
    stranger.setOrganizationId(organizationId);
    stranger = userRepository.save(stranger);

    try {
      var strangerId = stranger.getId();
      var documentId = uploaded.document().getId();
      assertThatThrownBy(() -> documentService.loadContent(documentId, strangerId, false))
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
  void loadContentAnswers404WithAGermanMessageWhenARemoteSourcedDocumentHasNoStoredSourceUrl() {
    // #747: HTTP_DIRECTORY/RSS_FEED content is now proxied from the document's own stored source
    // URL (see the proxy tests further down) - a document somehow persisted without one (file_path
    // blank rather than a real URL) has nothing to fetch, the one case loadRemoteContent itself
    // still answers 404 for directly, before ever attempting a network call.
    Document remoteDoc =
        new Document("extern.pdf", "", "application/pdf", 10L, DocumentSourceType.HTTP_DIRECTORY);
    remoteDoc.setLibraryId(libraryId);
    remoteDoc.setOrganizationId(organizationId);
    remoteDoc = documentRepository.save(remoteDoc);
    var documentId = remoteDoc.getId();

    assertThatThrownBy(() -> documentService.loadContent(documentId, editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              var responseStatusException = (ResponseStatusException) ex;
              assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(responseStatusException.getReason())
                  .isEqualTo("Für dieses Dokument steht kein Originaldokument zur Verfügung");
            });
  }

  // #747: GET /api/v1/documents/{documentId}/content for HTTP_DIRECTORY/RSS_FEED - proxied from
  // the document's stored source URL through the real, Spring-wired TargetAddressValidator/
  // BoundedDownloader beans rather than mocks, so the SSRF re-check and credential handling are
  // exercised end to end. 127.0.0.1 is allowlisted via configureProperties (target validation is
  // enabled by default, see application.yml) so this suite's own local HttpServer instances are
  // reachable without weakening the check for anything else.

  private HttpServer remoteServer;
  private String remoteBaseUrl;

  @AfterEach
  void tearDownRemoteServer() {
    if (remoteServer != null) {
      remoteServer.stop(0);
      remoteServer = null;
    }
  }

  private void startRemoteServer() throws IOException {
    remoteServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    remoteServer.start();
    remoteBaseUrl = "http://127.0.0.1:" + remoteServer.getAddress().getPort();
  }

  private KnowledgeLibrary saveRemoteLibrary(
      DocumentSourceType sourceType, String sourceCredentials) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Remote-Quelle",
            null,
            editor.getId(),
            LibraryVisibility.PRIVATE,
            true,
            sourceType,
            null,
            remoteBaseUrl + "/",
            null,
            sourceCredentials,
            false);
    library = libraryRepository.save(library);
    assetGrantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            organizationId,
            editor.getId(),
            AssetRole.OWNER,
            null,
            editor.getId()));
    return library;
  }

  @Test
  void loadContentProxiesTheOriginalFromTheRemoteHttpDirectorySource() throws IOException {
    startRemoteServer();
    remoteServer.createContext(
        "/original.pdf",
        exchange -> {
          byte[] bytes =
              "Originalinhalt vom entfernten Quellsystem".getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/pdf");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    KnowledgeLibrary remoteLibrary = saveRemoteLibrary(DocumentSourceType.HTTP_DIRECTORY, null);
    try {
      Document remoteDoc =
          new Document(
              "original.pdf",
              remoteBaseUrl + "/original.pdf",
              null,
              null,
              DocumentSourceType.HTTP_DIRECTORY);
      remoteDoc.setLibraryId(remoteLibrary.getId());
      remoteDoc.setOrganizationId(organizationId);
      remoteDoc = documentRepository.save(remoteDoc);

      DocumentContent content =
          documentService.loadContent(remoteDoc.getId(), editor.getId(), false);
      try {
        assertThat(content.isStreamed()).isTrue();
        assertThat(content.contentType()).isEqualTo("application/pdf");
        assertThat(new String(content.stream().readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("Originalinhalt vom entfernten Quellsystem");
      } finally {
        content.stream().close();
      }
    } finally {
      documentRepository.findByLibraryId(remoteLibrary.getId()).forEach(documentRepository::delete);
      libraryRepository.deleteById(remoteLibrary.getId());
    }
  }

  @Test
  void loadContentSendsTheLibrarysStoredCredentialsToTheRssFeedSourceButNeverToTheCaller()
      throws IOException {
    startRemoteServer();
    AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    remoteServer.createContext(
        "/original.pdf",
        exchange -> {
          receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    KnowledgeLibrary remoteLibrary =
        saveRemoteLibrary(DocumentSourceType.RSS_FEED, "libuser:libpass");
    try {
      Document remoteDoc =
          new Document(
              "original.pdf",
              remoteBaseUrl + "/original.pdf",
              null,
              null,
              DocumentSourceType.RSS_FEED);
      remoteDoc.setLibraryId(remoteLibrary.getId());
      remoteDoc.setOrganizationId(organizationId);
      remoteDoc = documentRepository.save(remoteDoc);

      DocumentContent content =
          documentService.loadContent(remoteDoc.getId(), editor.getId(), false);
      try {
        String expected =
            "Basic "
                + Base64.getEncoder()
                    .encodeToString("libuser:libpass".getBytes(StandardCharsets.UTF_8));
        assertThat(receivedAuthorization.get()).isEqualTo(expected);
        // #748 review, nit 1: reads the actual bytes the caller would receive rather than
        // asserting a tautology against a record with no credentials field to begin with.
        assertThat(new String(content.stream().readAllBytes(), StandardCharsets.UTF_8))
            .isEqualTo("content")
            .doesNotContain("libpass");
      } finally {
        content.stream().close();
      }
    } finally {
      documentRepository.findByLibraryId(remoteLibrary.getId()).forEach(documentRepository::delete);
      libraryRepository.deleteById(remoteLibrary.getId());
    }
  }

  @Test
  void loadContentAnswers404WithAGermanMessageWhenTheRemoteSourceIsOffline() throws IOException {
    startRemoteServer();
    remoteServer.stop(0);
    // The server has already stopped - its own baseUrl is now a closed local port nothing
    // answers on, standing in for "the source is offline" without any real internet access.
    KnowledgeLibrary remoteLibrary = saveRemoteLibrary(DocumentSourceType.HTTP_DIRECTORY, null);
    try {
      Document remoteDoc =
          new Document(
              "original.pdf",
              remoteBaseUrl + "/original.pdf",
              null,
              null,
              DocumentSourceType.HTTP_DIRECTORY);
      remoteDoc.setLibraryId(remoteLibrary.getId());
      remoteDoc.setOrganizationId(organizationId);
      var documentId = documentRepository.save(remoteDoc).getId();

      assertThatThrownBy(() -> documentService.loadContent(documentId, editor.getId(), false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex -> {
                var responseStatusException = (ResponseStatusException) ex;
                assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(responseStatusException.getReason())
                    .isEqualTo("Für dieses Dokument steht kein Originaldokument zur Verfügung");
              });
    } finally {
      documentRepository.findByLibraryId(remoteLibrary.getId()).forEach(documentRepository::delete);
      libraryRepository.deleteById(remoteLibrary.getId());
    }
    remoteServer = null;
  }

  @Test
  void loadContentAnswers404WhenTheStoredSourceUrlIsBlockedByTheTargetAllowlist()
      throws IOException {
    // #748 review, finding 4: the previous version of this test pointed at
    // "http://169.254.169.254/original.pdf" - never reachable in CI either, so it stayed green
    // even with the allowlist re-check removed entirely (the request would simply time out/refuse
    // the connection either way, producing the identical 404). This version instead binds a real,
    // listening HttpServer on 127.0.0.2 - loopback (always blocked once target validation is
    // enabled), but a different literal host string than the "127.0.0.1" this suite's own
    // configureProperties allowlists for its other local test servers, so it is neither allowlisted
    // nor unreachable. requestsReceived proves the request never left this process when the
    // re-check is in place; with it removed, the request would succeed and both assertions below
    // would fail.
    HttpServer blockedServer = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
    blockedServer.start();
    AtomicInteger requestsReceived = new AtomicInteger();
    blockedServer.createContext(
        "/original.pdf",
        exchange -> {
          requestsReceived.incrementAndGet();
          byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    String blockedBaseUrl = "http://127.0.0.2:" + blockedServer.getAddress().getPort();
    KnowledgeLibrary remoteLibrary =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Blockierte Quelle",
            null,
            editor.getId(),
            LibraryVisibility.PRIVATE,
            true,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            blockedBaseUrl + "/",
            null,
            null,
            false);
    remoteLibrary = libraryRepository.save(remoteLibrary);
    assetGrantRepository.save(
        AssetGrant.forUser(
            remoteLibrary.getId(),
            organizationId,
            editor.getId(),
            AssetRole.OWNER,
            null,
            editor.getId()));
    try {
      Document remoteDoc =
          new Document(
              "original.pdf",
              blockedBaseUrl + "/original.pdf",
              null,
              null,
              DocumentSourceType.HTTP_DIRECTORY);
      remoteDoc.setLibraryId(remoteLibrary.getId());
      remoteDoc.setOrganizationId(organizationId);
      var documentId = documentRepository.save(remoteDoc).getId();

      assertThatThrownBy(() -> documentService.loadContent(documentId, editor.getId(), false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex -> {
                var responseStatusException = (ResponseStatusException) ex;
                assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(responseStatusException.getReason())
                    .isEqualTo("Für dieses Dokument steht kein Originaldokument zur Verfügung");
              });
      assertThat(requestsReceived.get()).isZero();
    } finally {
      documentRepository.findByLibraryId(remoteLibrary.getId()).forEach(documentRepository::delete);
      libraryRepository.deleteById(remoteLibrary.getId());
      blockedServer.stop(0);
    }
  }

  @Test
  void loadContentAnswers404WhenTheFileHasBeenRemovedFromDisk() throws IOException {
    LibraryDocumentEntry uploaded =
        documentService.uploadDocument(
            libraryId, textFile("verschwunden.txt", "content"), null, editor.getId(), false);
    Document saved = awaitDocumentStatus(uploaded.document().getId(), DocumentStatus.INDEXED);
    Files.delete(Path.of(saved.getFilePath()));

    var documentId = uploaded.document().getId();
    assertThatThrownBy(() -> documentService.loadContent(documentId, editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void loadContentRefusesAFilePathThatEscapesTheLibraryUploadDirectory(@TempDir Path outsideDir)
      throws IOException {
    // A corrupted or foreign file_path column must not be trusted on its own (mirrors
    // uploadedFileIfManagedByThisService's own reasoning for deleteDocument).
    Path outsideFile = outsideDir.resolve("nicht-verwaltet.txt");
    Files.writeString(outsideFile, "Datei außerhalb des Upload-Verzeichnisses.");

    Document escapee =
        new Document(
            "nicht-verwaltet.txt",
            outsideFile.toString(),
            "text/plain",
            Files.size(outsideFile),
            DocumentSourceType.UPLOAD);
    escapee.setLibraryId(libraryId);
    escapee.setOrganizationId(organizationId);
    escapee = documentRepository.save(escapee);
    var documentId = escapee.getId();

    assertThatThrownBy(() -> documentService.loadContent(documentId, editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void loadContentTreatsADocumentInAnotherOrganizationsLibraryAsNotFound() {
    // #742 review, finding 3: the organization boundary at LibraryDocumentService#loadContent's
    // library lookup (mirrors KnowledgeLibraryService#loadLibrary) - unlike
    // loadContentRefusesAUserWithNoGrantAtAllWith404NotForbidden's stranger, who is a user *within*
    // the same organization, this one belongs to a wholly different organization and must never
    // even
    // reach the grant check.
    LibraryDocumentEntry uploaded =
        documentService.uploadDocument(
            libraryId, textFile("nur-fuer-uns.txt", "content"), null, editor.getId(), false);
    awaitDocumentStatus(uploaded.document().getId(), DocumentStatus.INDEXED);

    UUID otherOrganizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Andere Org")).getId();
    User strangerFromAnotherOrg =
        new User("other-org-subject", "issuer", "other-org@example.com", "Fremd");
    strangerFromAnotherOrg.setOrganizationId(otherOrganizationId);
    strangerFromAnotherOrg = userRepository.save(strangerFromAnotherOrg);

    try {
      var strangerId = strangerFromAnotherOrg.getId();
      var documentId = uploaded.document().getId();
      assertThatThrownBy(() -> documentService.loadContent(documentId, strangerId, false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex ->
                  assertThat(((ResponseStatusException) ex).getStatusCode())
                      .isEqualTo(HttpStatus.NOT_FOUND));
    } finally {
      userRepository.deleteById(strangerFromAnotherOrg.getId());
      organizationRepository.deleteById(otherOrganizationId);
    }
  }

  /**
   * Saves a FILESYSTEM library directly through the repository rather than through {@link
   * KnowledgeLibraryService#createLibrary} (#742 review, finding 3): that service additionally
   * requires an operator-style absolute Unix path ({@code sourcePath.startsWith("/")}), which a
   * JUnit {@code @TempDir} does not produce on every OS this suite runs on (Windows locally, Linux
   * in CI) - the schema itself (migration 027's {@code
   * chk_knowledge_libraries_source_configuration}) only requires a FILESYSTEM library's {@code
   * source_path} to be non-null, not any particular shape, so bypassing the service here still
   * leaves a row the database accepts.
   */
  private KnowledgeLibrary saveFilesystemLibrary(String sourcePath) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Verzeichnis",
            null,
            editor.getId(),
            LibraryVisibility.PRIVATE,
            true,
            DocumentSourceType.FILESYSTEM,
            sourcePath,
            null,
            null,
            null,
            false);
    library = libraryRepository.save(library);
    // KnowledgeLibraryService#createLibrary normally grants the creator OWNER as part of creation
    // (see its class Javadoc) - this helper otherwise skips that step entirely by saving the
    // library row directly, which LibraryAccessService#requireRole would then refuse for editor
    // exactly like a library nobody has any grant on at all.
    assetGrantRepository.save(
        AssetGrant.forUser(
            library.getId(),
            organizationId,
            editor.getId(),
            AssetRole.OWNER,
            null,
            editor.getId()));
    return library;
  }

  @Test
  void loadContentReturnsTheStoredFileForAFilesystemSourcedDocumentInsideItsConfiguredSourcePath()
      throws IOException {
    Path librarySourceDir =
        Files.createDirectory(filesystemAllowlistDir.resolve(UUID.randomUUID().toString()));
    Path sourceFile = librarySourceDir.resolve("dienstanweisung.txt");
    Files.writeString(sourceFile, "Original vom Betrieb verwaltete Datei.");

    KnowledgeLibrary connectorLibrary =
        saveFilesystemLibrary(librarySourceDir.toAbsolutePath().toString());
    try {
      Document doc =
          new Document(
              "dienstanweisung.txt",
              sourceFile.toString(),
              "text/plain",
              Files.size(sourceFile),
              DocumentSourceType.FILESYSTEM);
      doc.setLibraryId(connectorLibrary.getId());
      doc.setOrganizationId(organizationId);
      doc = documentRepository.save(doc);

      DocumentContent content = documentService.loadContent(doc.getId(), editor.getId(), false);

      assertThat(content.path()).isEqualTo(sourceFile.toRealPath());
      assertThat(content.fileName()).isEqualTo("dienstanweisung.txt");
    } finally {
      documentRepository
          .findByLibraryId(connectorLibrary.getId())
          .forEach(documentRepository::delete);
      libraryRepository.deleteById(connectorLibrary.getId());
    }
  }

  @Test
  void loadContentRefusesAFilesystemDocumentWhoseFilePathEscapesTheLibrarysOwnSourcePath()
      throws IOException {
    // A file_path that resolves somewhere else entirely within the operator-wide allowlist must
    // still be refused - the allowlist is necessary but not sufficient, this library's own
    // sourcePath is the actual containment boundary (mirrors
    // loadContentRefusesAFilePathThatEscapesTheLibraryUploadDirectory's UPLOAD counterpart).
    Path librarySourceDir =
        Files.createDirectory(filesystemAllowlistDir.resolve(UUID.randomUUID().toString()));
    Path outsideDir =
        Files.createDirectory(filesystemAllowlistDir.resolve(UUID.randomUUID().toString()));
    Path outsideFile = outsideDir.resolve("nicht-verwaltet.txt");
    Files.writeString(outsideFile, "Datei außerhalb des Quellverzeichnisses.");

    KnowledgeLibrary connectorLibrary =
        saveFilesystemLibrary(librarySourceDir.toAbsolutePath().toString());
    try {
      Document escapee =
          new Document(
              "nicht-verwaltet.txt",
              outsideFile.toString(),
              "text/plain",
              Files.size(outsideFile),
              DocumentSourceType.FILESYSTEM);
      escapee.setLibraryId(connectorLibrary.getId());
      escapee.setOrganizationId(organizationId);
      escapee = documentRepository.save(escapee);
      var documentId = escapee.getId();

      assertThatThrownBy(() -> documentService.loadContent(documentId, editor.getId(), false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex ->
                  assertThat(((ResponseStatusException) ex).getStatusCode())
                      .isEqualTo(HttpStatus.NOT_FOUND));
    } finally {
      documentRepository
          .findByLibraryId(connectorLibrary.getId())
          .forEach(documentRepository::delete);
      libraryRepository.deleteById(connectorLibrary.getId());
    }
  }

  @Test
  void loadContentAnswers404ForAFilesystemDocumentWhoseLibraryHasNoConfiguredSourcePath() {
    // A library's sourcePath can go missing without the row itself ever violating
    // chk_knowledge_libraries_source_configuration (migration 027): a FILESYSTEM library changed to
    // UPLOAD has its sourcePath cleared to null (KnowledgeLibraryService#updateLibrary), but a
    // document created while it was still FILESYSTEM keeps that historical sourceType on its own
    // row. "Nothing can be considered the configured index directory" must not be treated as
    // "everything allowed" for that leftover document - reuses the UPLOAD library setUp already
    // creates, whose sourcePath is null by construction.
    Document doc =
        new Document(
            "dienstanweisung.txt",
            "/does/not/matter.txt",
            "text/plain",
            10L,
            DocumentSourceType.FILESYSTEM);
    doc.setLibraryId(libraryId);
    doc.setOrganizationId(organizationId);
    doc = documentRepository.save(doc);
    var documentId = doc.getId();

    assertThatThrownBy(() -> documentService.loadContent(documentId, editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  // #517: page/size/q on GET /libraries/{id}/documents, backed by
  // KnowledgeLibraryService#listDocuments / DocumentRepository's paged finder methods. Seeded
  // directly via documentRepository rather than through uploadDocument/the indexing pipeline - the
  // paging and search behaviour under test does not depend on how a row got there.
  private Document seedDocument(String fileName) {
    return seedDocument(fileName, null);
  }

  // #821: the folder-scoped counterpart to seedDocument above - same reasoning, seeded directly
  // rather than through an actual upload.
  private Document seedDocument(String fileName, UUID folderId) {
    Document document =
        new Document(fileName, "/seed/" + fileName, "text/plain", 10L, DocumentSourceType.UPLOAD);
    document.setLibraryId(libraryId);
    document.setOrganizationId(organizationId);
    document.setStatus(DocumentStatus.INDEXED);
    document.setFolderId(folderId);
    return documentRepository.save(document);
  }

  private LibraryFolder seedFolder(String name, UUID parentFolderId) {
    return folderRepository.save(
        new LibraryFolder(libraryId, parentFolderId, name, organizationId));
  }

  // #517 code review, finding 1: mirrors LibraryController#listDocuments' stable ORDER BY - a
  // plain PageRequest.of(page, size) has no guaranteed row order across two separate SELECT ...
  // LIMIT/OFFSET statements in PostgreSQL, which is exactly what made the disjointedness
  // assertion below unreliable before that sort existed.
  private Pageable stableOrder(int page, int size) {
    return PageRequest.of(page, size, Sort.by(Sort.Order.asc("fileName"), Sort.Order.asc("id")));
  }

  @Test
  void listDocumentsReturnsAPageWithTheTotalElementCountAcrossAllPages() {
    for (int i = 0; i < 5; i++) {
      seedDocument("dokument-" + i + ".txt");
    }

    var firstPage =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, null, null, stableOrder(0, 2));
    assertThat(firstPage.documents()).hasSize(2);
    assertThat(firstPage.page()).isZero();
    assertThat(firstPage.size()).isEqualTo(2);
    assertThat(firstPage.totalElements()).isEqualTo(5);

    var secondPage =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, null, null, stableOrder(1, 2));
    assertThat(secondPage.documents()).hasSize(2);
    assertThat(secondPage.page()).isEqualTo(1);
    assertThat(secondPage.totalElements()).isEqualTo(5);

    var lastPage =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, null, null, stableOrder(2, 2));
    assertThat(lastPage.documents()).hasSize(1);

    assertThat(
            firstPage.documents().stream().map(entry -> entry.document().getId()).toList().stream()
                .noneMatch(
                    id ->
                        secondPage.documents().stream()
                            .anyMatch(d -> d.document().getId().equals(id))))
        .isTrue();

    // The order itself must be deterministic (fileName ascending), not merely disjoint pages.
    assertThat(firstPage.documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactly("dokument-0.txt", "dokument-1.txt");
    assertThat(secondPage.documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactly("dokument-2.txt", "dokument-3.txt");
  }

  @Test
  void listDocumentsFiltersByFileNameCaseInsensitiveSubstring() {
    seedDocument("Dienstanweisung-2024.pdf");
    seedDocument("Rundschreiben.pdf");
    seedDocument("dienstanweisung-alt.pdf");

    var result =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, "dienst", null, stableOrder(0, 20));

    assertThat(result.totalElements()).isEqualTo(2);
    assertThat(result.documents())
        .extracting(entry -> entry.document().getFileName())
        .allMatch(name -> name.toLowerCase().contains("dienst"));
  }

  @Test
  void listDocumentsTreatsPercentAndUnderscoreInQAsLiteralCharactersNotSqlWildcards() {
    // #517 code review, nit 3: Spring Data JPA's *Containing* finder escapes LIKE metacharacters
    // in the parameter by default (EscapeCharacter.DEFAULT) - this pins that behaviour down so a
    // future switch to a hand-written @Query cannot silently regress it. Without escaping, "%"
    // alone would match every row (LIKE '%%%' = "any content"), and "_" would match any single
    // character instead of a literal underscore.
    seedDocument("100%-Regel.pdf");
    seedDocument("normale-akte.pdf");
    seedDocument("akte_alt.pdf");
    seedDocument("aktexalt.pdf");

    var percentResult =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, "100%", null, stableOrder(0, 20));
    assertThat(percentResult.documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactly("100%-Regel.pdf");

    var underscoreResult =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, "akte_alt", null, stableOrder(0, 20));
    assertThat(underscoreResult.documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactly("akte_alt.pdf");
  }

  @Test
  void listDocumentsWithABlankQIgnoresTheFilter() {
    seedDocument("a.pdf");
    seedDocument("b.pdf");

    var result =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, "  ", null, PageRequest.of(0, 20));

    assertThat(result.totalElements()).isEqualTo(2);
  }

  @Test
  void listDocumentsRefusesAViewerWithoutAccessOnAnotherOrganizationsLibrary() {
    assertThatThrownBy(
            () ->
                libraryService.listDocuments(
                    UUID.randomUUID(), editor.getId(), false, null, null, PageRequest.of(0, 20)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  // #821 (Epic #520 Phase 2, ADR-0020): folder-aware GET .../documents - a folderId (or its
  // absence, meaning the root) scopes items to that exact level plus its direct subfolders and
  // breadcrumb, while q stays bibliotheksweit regardless of folderId.

  @Test
  void listDocumentsWithoutAFolderIdDefaultsToTheLibrarysRootBackwardCompatibly() {
    seedDocument("wurzel.txt");
    LibraryFolder protokolle = seedFolder("Protokolle", null);
    seedDocument("versteckt.txt", protokolle.getId());

    var result =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, null, null, stableOrder(0, 20));

    // Only the root-level document - not the one inside Protokolle - mirrors ADR-0020's
    // documented, accepted behaviour change from "the whole bestand" to "the root" (#821 spec).
    assertThat(result.documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactly("wurzel.txt");
    assertThat(result.folderId()).isNull();
    assertThat(result.folders())
        .extracting(f -> f.folder().getName())
        .containsExactly("Protokolle");
    assertThat(result.folders().get(0).documentCount()).isEqualTo(1L);
    assertThat(result.breadcrumb()).isEmpty();
  }

  @Test
  void listDocumentsSubfolderCountsAreRecursiveNotJustDirectChildren() {
    // #821 review round 1, finding 4: LibraryFolderListItem.documentCount must match
    // LibraryFolderResponse.documentCount's recursive semantics (ADR-0020, Entscheidung 5) - a
    // subfolder row here is exactly what a subsequent delete confirmation for it would show. Three
    // nesting levels below Protokolle, with a document at every level but the queried one (root),
    // so a shallow "direct children only" count (1, only Archiv's own direct document) would
    // visibly disagree with the correct recursive one (3, everything Archiv contains).
    LibraryFolder protokolle = seedFolder("Protokolle", null);
    LibraryFolder archiv = seedFolder("Archiv", protokolle.getId());
    LibraryFolder jahr2026 = seedFolder("2026", archiv.getId());
    seedDocument("archiv-direkt.pdf", archiv.getId());
    seedDocument("jahresbericht.pdf", jahr2026.getId());
    seedDocument("nachtrag.pdf", jahr2026.getId());

    var result =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, null, null, stableOrder(0, 20));

    assertThat(result.folders())
        .extracting(f -> f.folder().getName())
        .containsExactly("Protokolle");
    assertThat(result.folders().get(0).documentCount()).isEqualTo(3L);
  }

  @Test
  void listDocumentsScopedToAFolderListsOnlyItsOwnDocumentsAndDirectSubfoldersWithBreadcrumb() {
    LibraryFolder protokolle = seedFolder("Protokolle", null);
    LibraryFolder jahr2026 = seedFolder("2026", protokolle.getId());
    seedDocument("wurzel.txt", null);
    seedDocument("sitzung-januar.pdf", protokolle.getId());
    seedDocument("archiviert.pdf", jahr2026.getId());

    var result =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, null, protokolle.getId(), stableOrder(0, 20));

    assertThat(result.documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactly("sitzung-januar.pdf");
    assertThat(result.documents().get(0).document().getFolderId()).isEqualTo(protokolle.getId());
    assertThat(result.documents().get(0).folderPath()).isEqualTo("Protokolle");
    assertThat(result.folderId()).isEqualTo(protokolle.getId());
    assertThat(result.folders()).extracting(f -> f.folder().getName()).containsExactly("2026");
    assertThat(result.folders().get(0).documentCount()).isEqualTo(1L);
    assertThat(result.breadcrumb()).extracting(b -> b.getName()).containsExactly("Protokolle");
    assertThat(result.breadcrumb().get(0).getId()).isEqualTo(protokolle.getId());
  }

  @Test
  void listDocumentsSearchIsBibliotheksweitAcrossFoldersAndShowsTheFolderPathOfEachHit() {
    LibraryFolder protokolle = seedFolder("Protokolle", null);
    LibraryFolder jahr2026 = seedFolder("2026", protokolle.getId());
    seedDocument("dienstanweisung-wurzel.pdf", null);
    seedDocument("dienstanweisung-archiv.pdf", jahr2026.getId());
    seedDocument("irrelevant.pdf", protokolle.getId());

    // #821 spec: q ignores folderId entirely and stays bibliotheksweit, even when a folder is
    // also given (ADR-0020, Entscheidung 4 - no folder-scoped retrieval yet).
    var result =
        libraryService.listDocuments(
            libraryId,
            editor.getId(),
            false,
            "dienstanweisung",
            jahr2026.getId(),
            stableOrder(0, 20));

    assertThat(result.documents())
        .extracting(entry -> entry.document().getFileName())
        .containsExactlyInAnyOrder("dienstanweisung-wurzel.pdf", "dienstanweisung-archiv.pdf");
    assertThat(result.folderId()).isNull();
    assertThat(result.folders()).isEmpty();
    assertThat(result.breadcrumb()).isEmpty();
    assertThat(result.documents())
        .filteredOn(d -> d.document().getFileName().equals("dienstanweisung-archiv.pdf"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.document().getFolderId()).isEqualTo(jahr2026.getId());
              assertThat(d.folderPath()).isEqualTo("Protokolle/2026");
            });
    assertThat(result.documents())
        .filteredOn(d -> d.document().getFileName().equals("dienstanweisung-wurzel.pdf"))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.document().getFolderId()).isNull();
              assertThat(d.folderPath()).isNull();
            });
  }

  @Test
  void listDocumentsWithAnUnknownFolderIdAnswers404() {
    assertThatThrownBy(
            () ->
                libraryService.listDocuments(
                    libraryId, editor.getId(), false, null, UUID.randomUUID(), stableOrder(0, 20)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void listDocumentsWithAnUnknownFolderIdAndAQAlsoAnswers404() {
    // #821 review round 1, finding 3: folderId is validated whether or not q is also set - a
    // caller must not be able to bypass the 404 simply by adding a search term, which the first
    // implementation round accidentally allowed (q short-circuited the validation entirely).
    seedDocument("dienstanweisung.pdf");

    assertThatThrownBy(
            () ->
                libraryService.listDocuments(
                    libraryId,
                    editor.getId(),
                    false,
                    "dienst",
                    UUID.randomUUID(),
                    stableOrder(0, 20)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void listDocumentsWithAFolderFromAnotherLibraryAnswers404() {
    var otherLibraryRequest =
        libraryCreation("Andere Bibliothek", DocumentSourceType.UPLOAD).build();
    var otherLibrary = libraryService.createLibrary(otherLibraryRequest, editor.getId());
    LibraryFolder foreignFolder =
        folderRepository.save(
            new LibraryFolder(otherLibrary.library().getId(), null, "Fremd", organizationId));

    assertThatThrownBy(
            () ->
                libraryService.listDocuments(
                    libraryId,
                    editor.getId(),
                    false,
                    null,
                    foreignFolder.getId(),
                    stableOrder(0, 20)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    documentRepository
        .findByLibraryId(otherLibrary.library().getId())
        .forEach(documentRepository::delete);
    folderRepository.delete(foreignFolder);
    libraryRepository.deleteById(otherLibrary.library().getId());
  }

  // #821: POST .../documents' own folderId - upload target validation and the resulting
  // folderId/folderPath on the response.

  @Test
  void uploadDocumentIntoAFolderSetsItsFolderIdAndDerivedFolderPath() {
    LibraryFolder protokolle = seedFolder("Protokolle", null);

    LibraryDocumentEntry response =
        documentService.uploadDocument(
            libraryId,
            textFile("sitzung.txt", "Inhalt der Sitzung"),
            protokolle.getId(),
            editor.getId(),
            false);

    assertThat(response.document().getFolderId()).isEqualTo(protokolle.getId());
    assertThat(response.folderPath()).isEqualTo("Protokolle");

    Document saved = documentRepository.findById(response.document().getId()).orElseThrow();
    assertThat(saved.getFolderId()).isEqualTo(protokolle.getId());
  }

  @Test
  void uploadDocumentWithAnUnknownFolderIdAnswers404AndStoresNothing() {
    assertThatThrownBy(
            () ->
                documentService.uploadDocument(
                    libraryId,
                    textFile("x.txt", "content"),
                    UUID.randomUUID(),
                    editor.getId(),
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    assertThat(documentRepository.findByLibraryId(libraryId)).isEmpty();
  }

  @Test
  void uploadDocumentWithAFolderFromAnotherLibraryAnswers404() {
    var otherLibraryRequest =
        libraryCreation("Andere Bibliothek", DocumentSourceType.UPLOAD).build();
    var otherLibrary = libraryService.createLibrary(otherLibraryRequest, editor.getId());
    LibraryFolder foreignFolder =
        folderRepository.save(
            new LibraryFolder(otherLibrary.library().getId(), null, "Fremd", organizationId));

    assertThatThrownBy(
            () ->
                documentService.uploadDocument(
                    libraryId,
                    textFile("x.txt", "content"),
                    foreignFolder.getId(),
                    editor.getId(),
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));

    assertThat(documentRepository.findByLibraryId(libraryId)).isEmpty();

    folderRepository.delete(foreignFolder);
    libraryRepository.deleteById(otherLibrary.library().getId());
  }

  // #823 (Epic #520 Phase 4): POST .../documents' folderPath - idempotent intermediate folder
  // creation for a whole dragged-and-dropped or webkitdirectory-selected directory tree, uploaded
  // one file at a time.

  @Test
  void uploadDocumentWithAFolderPathCreatesTheChainAndPlacesTheDocumentInTheLeafFolder() {
    LibraryDocumentEntry response =
        documentService.uploadDocument(
            libraryId,
            textFile("protokoll.txt", "Sitzungsprotokoll"),
            null,
            "Protokolle/2026",
            editor.getId(),
            false);

    assertThat(response.folderPath()).isEqualTo("Protokolle/2026");
    Document saved = documentRepository.findById(response.document().getId()).orElseThrow();
    LibraryFolder leaf = folderRepository.findById(saved.getFolderId()).orElseThrow();
    assertThat(leaf.getName()).isEqualTo("2026");
    LibraryFolder root = folderRepository.findById(leaf.getParentFolderId()).orElseThrow();
    assertThat(root.getName()).isEqualTo("Protokolle");
    assertThat(root.getParentFolderId()).isNull();
    assertThat(folderRepository.findByLibraryId(libraryId)).hasSize(2);
  }

  @Test
  void twoUploadsIntoTheSameNewFolderPathShareOneFolderTreeInsteadOfDuplicatingIt() {
    // #823 acceptance criteria: a second file dragged into the same new path must reuse the
    // folders the first file's upload already created, not create a sibling tree.
    LibraryDocumentEntry first =
        documentService.uploadDocument(
            libraryId,
            textFile("erste-datei.txt", "erster Inhalt"),
            null,
            "Protokolle/2026",
            editor.getId(),
            false);
    LibraryDocumentEntry second =
        documentService.uploadDocument(
            libraryId,
            textFile("zweite-datei.txt", "zweiter Inhalt"),
            null,
            "Protokolle/2026",
            editor.getId(),
            false);

    Document firstDoc = documentRepository.findById(first.document().getId()).orElseThrow();
    Document secondDoc = documentRepository.findById(second.document().getId()).orElseThrow();
    assertThat(secondDoc.getFolderId()).isEqualTo(firstDoc.getFolderId());
    assertThat(folderRepository.findByLibraryId(libraryId)).hasSize(2);
  }

  @Test
  void uploadDocumentWithAFolderPathReusesAnAlreadyExistingFolderOfTheSameName() {
    // #823 acceptance criteria: a folder a person already created manually (e.g. via the folder
    // CRUD endpoint) must be reused, not shadowed by a second folder of the same name.
    LibraryFolder existing = seedFolder("Protokolle", null);

    LibraryDocumentEntry response =
        documentService.uploadDocument(
            libraryId,
            textFile("sitzung.txt", "Inhalt"),
            null,
            "Protokolle",
            editor.getId(),
            false);

    Document saved = documentRepository.findById(response.document().getId()).orElseThrow();
    assertThat(saved.getFolderId()).isEqualTo(existing.getId());
    assertThat(folderRepository.findByLibraryId(libraryId)).hasSize(1);
  }

  @Test
  void uploadDocumentWithAFolderPathRelativeToAnExplicitFolderIdNestsUnderIt() {
    // #823: folderId + folderPath together - folderPath is relative to folderId, not the root.
    LibraryFolder bestand = seedFolder("Bestand", null);

    LibraryDocumentEntry response =
        documentService.uploadDocument(
            libraryId,
            textFile("dokument.txt", "Inhalt"),
            bestand.getId(),
            "Unterordner",
            editor.getId(),
            false);

    Document saved = documentRepository.findById(response.document().getId()).orElseThrow();
    LibraryFolder leaf = folderRepository.findById(saved.getFolderId()).orElseThrow();
    assertThat(leaf.getName()).isEqualTo("Unterordner");
    assertThat(leaf.getParentFolderId()).isEqualTo(bestand.getId());
  }

  @Test
  void uploadDocumentWithAFolderPathContainingAnInvalidSegmentAnswers400AndStoresNothing() {
    assertThatThrownBy(
            () ->
                documentService.uploadDocument(
                    libraryId,
                    textFile("x.txt", "content"),
                    null,
                    "Protokolle/../etc",
                    editor.getId(),
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    assertThat(documentRepository.findByLibraryId(libraryId)).isEmpty();
    // Neither the valid "Protokolle" segment nor anything else must have been created - the whole
    // path is validated before any folder is materialized.
    assertThat(folderRepository.findByLibraryId(libraryId)).isEmpty();
  }

  @Test
  void uploadDocumentWithAnUnsupportedFormatAndAFolderPathCreatesNoFolders() {
    // #823 review, Befund 1: resolveOrCreateFolderPath used to run before the format check
    // (empty/size/quota/format), so a rejected upload into a brand-new folderPath still left that
    // folder chain behind - three hundred wrong-format files dropped into "Protokolle/2026" would
    // have created that same folder chain three hundred times over before the first ever actually
    // failed. Moving the resolve call past the format check (see uploadDocument's own comment)
    // means a rejected format never reaches it at all.
    MultipartFile unsupported =
        new MockMultipartFile("file", "malware.exe", "application/octet-stream", "x".getBytes());

    assertThatThrownBy(
            () ->
                documentService.uploadDocument(
                    libraryId, unsupported, null, "Protokolle/2026", editor.getId(), false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    assertThat(documentRepository.findByLibraryId(libraryId)).isEmpty();
    assertThat(folderRepository.findByLibraryId(libraryId)).isEmpty();
  }

  @Test
  void uploadDocumentWithAFolderPathIntoAConnectorLibraryIsRejectedWithConflict() {
    var connectorLibraryRequest =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents")
            .build();
    var connectorLibrary = libraryService.createLibrary(connectorLibraryRequest, editor.getId());
    try {
      assertThatThrownBy(
              () ->
                  documentService.uploadDocument(
                      connectorLibrary.library().getId(),
                      textFile("x.txt", "content"),
                      null,
                      "Protokolle",
                      editor.getId(),
                      false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex ->
                  assertThat(((ResponseStatusException) ex).getStatusCode())
                      .isEqualTo(HttpStatus.CONFLICT));
      assertThat(documentRepository.findByLibraryId(connectorLibrary.library().getId())).isEmpty();
      assertThat(folderRepository.findByLibraryId(connectorLibrary.library().getId())).isEmpty();
    } finally {
      libraryRepository.deleteById(connectorLibrary.library().getId());
    }
  }

  @Test
  void uploadDocumentWithAFolderPathIntoAnotherOrganizationsLibraryAnswers404() {
    UUID otherOrganizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Andere Org")).getId();
    User strangerFromAnotherOrg =
        new User("folder-path-stranger-subject", "issuer", "fp-stranger@example.com", "Fremd");
    strangerFromAnotherOrg.setOrganizationId(otherOrganizationId);
    strangerFromAnotherOrg = userRepository.save(strangerFromAnotherOrg);

    try {
      var strangerId = strangerFromAnotherOrg.getId();
      assertThatThrownBy(
              () ->
                  documentService.uploadDocument(
                      libraryId,
                      textFile("x.txt", "content"),
                      null,
                      "Protokolle",
                      strangerId,
                      false))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(
              ex ->
                  assertThat(((ResponseStatusException) ex).getStatusCode())
                      .isEqualTo(HttpStatus.NOT_FOUND));
      assertThat(folderRepository.findByLibraryId(libraryId)).isEmpty();
    } finally {
      userRepository.deleteById(strangerFromAnotherOrg.getId());
      organizationRepository.deleteById(otherOrganizationId);
    }
  }

  /**
   * Polls the document row until asynchronous processing (#434) has moved it past {@code PENDING}
   * to some terminal status, then asserts it is the expected one - mirrors {@code
   * DocumentIndexingIntegrationTest#awaitJobCompletion}'s use of Awaitility for the same reason:
   * {@code uploadTaskExecutor} runs on its own thread pool, so a test asserting on the eventual
   * outcome cannot simply read the row synchronously right after {@code uploadDocument} returns.
   *
   * <p>Waits for "no longer PENDING", not for the expected status directly (PR #589 review, item
   * 6): waiting for the expected status directly would time out after the full 30 seconds with an
   * unhelpful "still PENDING" message if processing actually finished quickly but landed on the
   * *other* terminal status - this instead fails fast with a clear expected/actual mismatch the
   * moment processing is done, whichever status it reached.
   */
  private Document awaitDocumentStatus(UUID documentId, DocumentStatus expected) {
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                documentRepository
                    .findById(documentId)
                    .map(Document::getStatus)
                    .filter(status -> status != DocumentStatus.PENDING)
                    .isPresent());
    Document document = documentRepository.findById(documentId).orElseThrow();
    assertThat(document.getStatus()).isEqualTo(expected);
    return document;
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
