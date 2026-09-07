package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.IndexingRunMode;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.JobTriggerSource;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.source.IndexingRunTemplate;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryFolder;
import io.opaa.library.LibraryFolderRepository;
import io.opaa.library.LibraryFolderService;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.message.MultipartBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end coverage of the S3 run over the real, Spring-wired document path (ADR-0027,
 * Entscheidung 5): key prefixes become read-only folders with the same materialise/prune rules
 * {@code FILESYSTEM} and {@code HTTP_DIRECTORY} follow, several scopes keep their documents under
 * separate root chains, a renamed object is a new document in its new folder, a mail object's
 * attachments are child documents in the mail's folder and follow its re-parse, and an
 * extension-less object is admitted by its content type. Only the executor is hand-built, over a
 * {@link FakeS3ObjectStore} handed out by a mocked factory (mirrors {@code
 * UrlFolderMappingIntegrationTest}).
 */
@OpaaIndexingIntegrationTest
class S3FolderMappingIntegrationTest {

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingRunTemplate indexingRunTemplate;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private LibraryFolderRepository folderRepository;
  @Autowired private LibraryFolderService folderService;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final FakeS3ObjectStore store = new FakeS3ObjectStore();
  private final List<KnowledgeLibrary> createdLibraries = new ArrayList<>();
  private UUID userId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'S3 Folder IT', now(), ?, ?)",
        userId,
        "s3-folder-it-" + userId,
        "s3-folder-it-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    library = libraryWith(List.of(S3Scope.of("dokumente", "")));
  }

  private KnowledgeLibrary libraryWith(List<S3Scope> scopes) {
    KnowledgeLibrary fresh =
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            "Objektspeicher",
            null,
            userId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.S3,
            null,
            "https://minio.intern.example:9000",
            null,
            "AKIAEXAMPLE:geheim",
            false);
    fresh.updateS3Settings(new S3SourceSettings(null, true, scopes, null, null));
    KnowledgeLibrary saved = libraryRepository.save(fresh);
    createdLibraries.add(saved);
    return saved;
  }

  @AfterEach
  void tearDown() {
    for (KnowledgeLibrary created : createdLibraries) {
      cleanUp(created);
    }
    jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
  }

  /**
   * Library-scoped cleanup, children before parents for fk_documents_parent, folders after their
   * documents for fk_documents_folder.
   */
  private void cleanUp(KnowledgeLibrary created) {
    List<Document> documents =
        documentRepository.findByLibraryIdAndSourceType(created.getId(), DocumentSourceType.S3);
    documents.stream()
        .sorted(Comparator.comparingInt((Document d) -> d.getFilePath().length()).reversed())
        .forEach(
            document -> {
              vectorChunkStore.deleteByDocumentId(document.getId());
              documentRepository.delete(document);
            });
    folderRepository.findByLibraryId(created.getId()).stream()
        .sorted(Comparator.comparingInt((LibraryFolder f) -> depthOf(f)).reversed())
        .forEach(folderRepository::delete);
    jdbcTemplate.update(
        "DELETE FROM indexing_run_events WHERE job_id IN"
            + " (SELECT id FROM indexing_jobs WHERE library_id = ?)",
        created.getId());
    jdbcTemplate.update("DELETE FROM indexing_jobs WHERE library_id = ?", created.getId());
    libraryRepository.deleteById(created.getId());
  }

  private int depthOf(LibraryFolder folder) {
    int depth = 0;
    UUID parent = folder.getParentFolderId();
    while (parent != null) {
      depth++;
      Optional<LibraryFolder> next = folderRepository.findById(parent);
      if (next.isEmpty()) {
        break;
      }
      parent = next.get().getParentFolderId();
    }
    return depth;
  }

  private S3IndexingExecutor executor() throws S3AccessException {
    S3ClientFactory clientFactory = mock(S3ClientFactory.class);
    when(clientFactory.createForRun(any(), any())).thenReturn(store);
    return new S3IndexingExecutor(
        clientFactory,
        S3Properties.defaults(),
        fileProcessingService,
        documentRepository,
        folderService,
        indexingRunTemplate);
  }

  /** One full run, synchronous - the executor is called directly. */
  private IndexingJob run() throws S3AccessException {
    IndexingJob job =
        indexingJobService.startJob(
            library.getId(),
            Organization.DEFAULT_ID,
            JobTriggerSource.MANUAL,
            IndexingRunMode.FULL);
    executor().execute(job.getId(), library, IndexingRunMode.FULL);
    IndexingJob finished = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(finished.getStatus())
        .as("run %s: %s", job.getId(), finished.getErrorMessage())
        .isEqualTo(JobStatus.COMPLETED);
    return finished;
  }

  private Optional<LibraryFolder> findFolder(UUID parentFolderId, String name) {
    return folderRepository.findByLibraryId(library.getId()).stream()
        .filter(folder -> Objects.equals(folder.getParentFolderId(), parentFolderId))
        .filter(folder -> folder.getName().equals(name))
        .findFirst();
  }

  private Optional<Document> documentAt(String bucket, String key) {
    return documentRepository.findByLibraryIdAndFilePath(
        library.getId(), S3FullSync.filePath(bucket, key));
  }

  private static byte[] mailWithAttachments(String... attachmentNames) throws Exception {
    MultipartBuilder body =
        MultipartBuilder.create("mixed")
            .addTextPart("Bitte prüfen Sie die Anlagen.", StandardCharsets.UTF_8);
    for (String name : attachmentNames) {
      body.addBodyPart(
          BodyPartBuilder.create()
              .setBody(("Inhalt von " + name).getBytes(StandardCharsets.UTF_8), "text/plain")
              .setContentDisposition("attachment", name));
    }
    Message message =
        Message.Builder.of()
            .setSubject("Anfrage mit " + attachmentNames.length + " Anlagen")
            .setFrom("Bürgeramt <buergeramt@example.org>")
            .setTo("Sachbearbeitung <sachbearbeitung@example.org>")
            .setBody(body.build())
            .build();
    return DefaultMessageWriter.asBytes(message);
  }

  @Test
  void prefixLevelsBecomeAFolderTreeAndAnEmptiedPrefixDisappearsAfterACompleteRun()
      throws Exception {
    store
        .put("dokumente", "2025/protokolle/q1/a.txt", "Protokoll Q1.", "text/plain")
        .put("dokumente", "2025/protokolle/q2/b.txt", "Protokoll Q2.", "text/plain")
        .put("dokumente", "2025/protokolle/", new byte[0], "application/x-directory")
        .put("dokumente", "leer/", new byte[0], "application/x-directory")
        .put("dokumente", "wurzel.txt", "Wurzeldokument.", "text/plain");

    run();

    LibraryFolder jahr = findFolder(null, "2025").orElseThrow();
    LibraryFolder protokolle = findFolder(jahr.getId(), "protokolle").orElseThrow();
    LibraryFolder q1 = findFolder(protokolle.getId(), "q1").orElseThrow();
    LibraryFolder q2 = findFolder(protokolle.getId(), "q2").orElseThrow();
    assertThat(documentAt("dokumente", "2025/protokolle/q1/a.txt").orElseThrow().getFolderId())
        .isEqualTo(q1.getId());
    assertThat(documentAt("dokumente", "wurzel.txt").orElseThrow().getFolderId()).isNull();
    assertThat(documentAt("dokumente", "2025/protokolle/"))
        .as("a folder marker never becomes a document")
        .isEmpty();
    assertThat(findFolder(null, "leer")).as("nor does it create a folder").isEmpty();
    assertThat(folderRepository.findByLibraryId(library.getId())).hasSize(4);

    store.remove("dokumente", "2025/protokolle/q2/b.txt");
    run();

    assertThat(folderRepository.findById(q2.getId())).isEmpty();
    assertThat(folderRepository.findById(q1.getId())).isPresent();
    assertThat(documentAt("dokumente", "2025/protokolle/q2/b.txt")).isEmpty();
  }

  @Test
  void severalScopesKeepTheirDocumentsUnderSeparateRootChains() throws Exception {
    library = libraryWith(List.of(S3Scope.of("dokumente", "2025/"), S3Scope.of("satzungen", "")));
    store
        .put("dokumente", "2025/protokoll.txt", "Protokoll.", "text/plain")
        .put("satzungen", "protokoll.txt", "Satzung.", "text/plain");

    run();

    LibraryFolder dokumente = findFolder(null, "dokumente").orElseThrow();
    LibraryFolder jahr = findFolder(dokumente.getId(), "2025").orElseThrow();
    LibraryFolder satzungen = findFolder(null, "satzungen").orElseThrow();
    assertThat(documentAt("dokumente", "2025/protokoll.txt").orElseThrow().getFolderId())
        .isEqualTo(jahr.getId());
    assertThat(documentAt("satzungen", "protokoll.txt").orElseThrow().getFolderId())
        .isEqualTo(satzungen.getId());
    assertThat(findFolder(null, "dokumente/2025")).as("never a composite name").isEmpty();
  }

  @Test
  void aRenamedObjectIsANewDocumentInItsNewFolderAndTheOldOneGoes() throws Exception {
    store.put("dokumente", "alt/bericht.txt", "Bericht.", "text/plain");
    run();
    UUID previousId = documentAt("dokumente", "alt/bericht.txt").orElseThrow().getId();
    UUID altId = findFolder(null, "alt").orElseThrow().getId();

    store
        .remove("dokumente", "alt/bericht.txt")
        .put("dokumente", "neu/bericht.txt", "Bericht.", "text/plain");
    run();

    Document renamed = documentAt("dokumente", "neu/bericht.txt").orElseThrow();
    assertThat(renamed.getId()).isNotEqualTo(previousId);
    assertThat(renamed.getFolderId()).isEqualTo(findFolder(null, "neu").orElseThrow().getId());
    assertThat(documentAt("dokumente", "alt/bericht.txt")).isEmpty();
    assertThat(folderRepository.findById(altId)).isEmpty();
  }

  @Test
  void aMailObjectsAttachmentsAreChildDocumentsInTheMailsFolderAndFollowItsReparse()
      throws Exception {
    store.put(
        "dokumente",
        "post/anfrage.eml",
        mailWithAttachments("anlage-1.txt", "anlage-2.txt"),
        "message/rfc822");

    run();

    Document mail = documentAt("dokumente", "post/anfrage.eml").orElseThrow();
    UUID post = findFolder(null, "post").orElseThrow().getId();
    assertThat(mail.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(mail.getFolderId()).isEqualTo(post);
    List<Document> attachments = documentRepository.findByParentDocumentId(mail.getId());
    assertThat(attachments)
        .extracting(Document::getFileName)
        .containsExactlyInAnyOrder("anlage-1.txt", "anlage-2.txt");
    assertThat(attachments).allSatisfy(child -> assertThat(child.getFolderId()).isEqualTo(post));

    // the re-parsed mail carries one attachment: the other child goes with the reconciliation
    store.put(
        "dokumente", "post/anfrage.eml", mailWithAttachments("anlage-1.txt"), "message/rfc822");
    run();

    assertThat(documentRepository.findByParentDocumentId(mail.getId()))
        .extracting(Document::getFileName)
        .containsExactly("anlage-1.txt");
  }

  @Test
  void anExtensionLessObjectWithAPdfContentTypeIsProcessed() throws Exception {
    byte[] pdf;
    try (var in = getClass().getResourceAsStream("/test-documents/test-document.pdf")) {
      pdf = Objects.requireNonNull(in, "fixture").readAllBytes();
    }
    store.put("dokumente", "eingang/protokoll", pdf, "application/pdf");

    run();

    Document document = documentAt("dokumente", "eingang/protokoll").orElseThrow();
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(document.getFileName()).isEqualTo("protokoll");
    assertThat(document.getContentType()).isEqualTo("application/pdf");
    assertThat(store.calls()).contains("head dokumente/eingang/protokoll");
  }
}
