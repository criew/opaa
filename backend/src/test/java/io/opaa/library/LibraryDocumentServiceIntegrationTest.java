package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
 *
 * <p>{@code uploadDocument} itself only ever returns {@code PENDING} now (#434) - parsing and
 * embedding run asynchronously on {@code indexingTaskExecutor}, the real thread pool this test's
 * Spring context wires up (unlike the unit tests in {@code LibraryDocumentServiceTest}, which mock
 * {@code FileProcessingService} outright). {@link #awaitDocumentStatus} polls the row via
 * Awaitility the same way {@code DocumentIndexingIntegrationTest#awaitJobCompletion} already does
 * for a directory/URL indexing run, wherever a test needs the eventual {@code INDEXED}/{@code
 * FAILED} outcome rather than the immediate {@code PENDING} response.
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

    // #434: uploadDocument itself only ever returns PENDING - parsing/embedding still run on
    // indexingTaskExecutor after this call has already returned.
    assertThat(response.getStatus()).isEqualTo(DocumentStatus.PENDING);
    assertThat(response.getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
    assertThat(response.getUploadedByUserId()).isEqualTo(editor.getId());

    Document saved = awaitDocumentStatus(response.getId(), DocumentStatus.INDEXED);
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
      awaitDocumentStatus(response.getId(), DocumentStatus.INDEXED);
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
    Document savedDoc = awaitDocumentStatus(uploaded.getId(), DocumentStatus.INDEXED);
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

    // #479 review nit: documentsRemoved in the LIBRARY_DELETED audit entry must come from
    // DocumentRepository#deleteByLibraryId's own bulk-delete row count, not from a count taken
    // before the delete - see KnowledgeLibraryService#deleteLibrary.
    String deletionPayload =
        jdbcTemplate.queryForObject(
            "SELECT before FROM audit_log WHERE object_id = ? AND event_type = 'LIBRARY_DELETED'",
            String.class,
            connectorLibrary.getId().toString());
    assertThat(deletionPayload).contains("\"documentsRemoved\":1");
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

  // #517: page/size/q on GET /libraries/{id}/documents, backed by
  // KnowledgeLibraryService#listDocuments / DocumentRepository's paged finder methods. Seeded
  // directly via documentRepository rather than through uploadDocument/the indexing pipeline - the
  // paging and search behaviour under test does not depend on how a row got there.
  private Document seedDocument(String fileName) {
    Document document =
        new Document(fileName, "/seed/" + fileName, "text/plain", 10L, DocumentSourceType.UPLOAD);
    document.setLibraryId(libraryId);
    document.setOrganizationId(organizationId);
    document.setStatus(DocumentStatus.INDEXED);
    return documentRepository.save(document);
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
        libraryService.listDocuments(libraryId, editor.getId(), false, null, stableOrder(0, 2));
    assertThat(firstPage.getItems()).hasSize(2);
    assertThat(firstPage.getPage()).isZero();
    assertThat(firstPage.getSize()).isEqualTo(2);
    assertThat(firstPage.getTotalElements()).isEqualTo(5);

    var secondPage =
        libraryService.listDocuments(libraryId, editor.getId(), false, null, stableOrder(1, 2));
    assertThat(secondPage.getItems()).hasSize(2);
    assertThat(secondPage.getPage()).isEqualTo(1);
    assertThat(secondPage.getTotalElements()).isEqualTo(5);

    var lastPage =
        libraryService.listDocuments(libraryId, editor.getId(), false, null, stableOrder(2, 2));
    assertThat(lastPage.getItems()).hasSize(1);

    assertThat(
            firstPage.getItems().stream().map(LibraryDocumentResponse::getId).toList().stream()
                .noneMatch(
                    id -> secondPage.getItems().stream().anyMatch(d -> d.getId().equals(id))))
        .isTrue();

    // The order itself must be deterministic (fileName ascending), not merely disjoint pages.
    assertThat(firstPage.getItems())
        .extracting(LibraryDocumentResponse::getFileName)
        .containsExactly("dokument-0.txt", "dokument-1.txt");
    assertThat(secondPage.getItems())
        .extracting(LibraryDocumentResponse::getFileName)
        .containsExactly("dokument-2.txt", "dokument-3.txt");
  }

  @Test
  void listDocumentsFiltersByFileNameCaseInsensitiveSubstring() {
    seedDocument("Dienstanweisung-2024.pdf");
    seedDocument("Rundschreiben.pdf");
    seedDocument("dienstanweisung-alt.pdf");

    var result =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, "dienst", stableOrder(0, 20));

    assertThat(result.getTotalElements()).isEqualTo(2);
    assertThat(result.getItems())
        .extracting(LibraryDocumentResponse::getFileName)
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
        libraryService.listDocuments(libraryId, editor.getId(), false, "100%", stableOrder(0, 20));
    assertThat(percentResult.getItems())
        .extracting(LibraryDocumentResponse::getFileName)
        .containsExactly("100%-Regel.pdf");

    var underscoreResult =
        libraryService.listDocuments(
            libraryId, editor.getId(), false, "akte_alt", stableOrder(0, 20));
    assertThat(underscoreResult.getItems())
        .extracting(LibraryDocumentResponse::getFileName)
        .containsExactly("akte_alt.pdf");
  }

  @Test
  void listDocumentsWithABlankQIgnoresTheFilter() {
    seedDocument("a.pdf");
    seedDocument("b.pdf");

    var result =
        libraryService.listDocuments(libraryId, editor.getId(), false, "  ", PageRequest.of(0, 20));

    assertThat(result.getTotalElements()).isEqualTo(2);
  }

  @Test
  void listDocumentsRefusesAViewerWithoutAccessOnAnotherOrganizationsLibrary() {
    assertThatThrownBy(
            () ->
                libraryService.listDocuments(
                    UUID.randomUUID(), editor.getId(), false, null, PageRequest.of(0, 20)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
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
