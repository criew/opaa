package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.FakeEmbeddingModel;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.indexing.document.Document;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.source.s3.MinioFixture;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.SystemHealthDescriptor;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The whole upload path with {@code opaa.upload.store=s3} against a real MinIO (ADR-0030): an
 * upload lands in the bucket, is processed to {@code INDEXED} from its working file - which is gone
 * afterwards - is served back from the bucket and removed from it on deletion; and the store's
 * health contributor sits in its own group, not in the overall status. Skipped without Docker.
 */
// Own @DynamicPropertySource (the S3 store and the MinIO endpoint) and a class-local
// @TestConfiguration mean Spring's context cache keys this to its own context regardless of the
// shared @OpaaIntegrationTest base - documented exception per AGENTS.md.
@OpaaIntegrationTest
class S3UploadStorageIntegrationTest {

  private static MinioFixture minio;
  private static String bucket;

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    minio = MinioFixture.get();
    bucket = minio.createBucket("opaa-upload-store");
    registry.add("opaa.upload.store", () -> "s3");
    registry.add("opaa.upload.s3.endpoint", () -> minio.endpoint().toString());
    registry.add("opaa.upload.s3.bucket", () -> bucket);
    registry.add("opaa.upload.s3.access-key", () -> minio.rootCredentials().accessKey());
    registry.add("opaa.upload.s3.secret-key", () -> minio.rootCredentials().secretKey());
    registry.add("opaa.upload.s3.temp-directory", () -> tempDir.toAbsolutePath().toString());
    registry.add("opaa.upload.max-file-size", () -> 4096);
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
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private UploadedOriginalStore uploadedOriginalStore;
  @Autowired private HealthEndpoint healthEndpoint;

  private UUID organizationId;
  private User editor;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
    editor = new User("editor-subject-s3", "issuer", "editor-s3@example.com", "Editor");
    editor.setOrganizationId(organizationId);
    editor = userRepository.save(editor);
    var library =
        libraryService.createLibrary(
            libraryCreation("S3-Bibliothek", DocumentSourceType.UPLOAD).build(),
            currentUserOf(editor));
    libraryId = library.library().getId();
  }

  @AfterEach
  void tearDown() {
    List<Document> remaining = documentRepository.findAll();
    while (!remaining.isEmpty()) {
      Set<UUID> referencedAsParent =
          remaining.stream()
              .map(Document::getParentDocumentId)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      documentRepository.deleteAll(
          remaining.stream().filter(d -> !referencedAsParent.contains(d.getId())).toList());
      remaining = documentRepository.findAll();
    }
    libraryRepository.deleteById(libraryId);
    grantHistoryRepository.deleteBySubjectUserIdIn(List.of(editor.getId()));
    membershipHistoryRepository.deleteByUserIdIn(List.of(editor.getId()));
    userRepository.deleteById(editor.getId());
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void theSelectedStoreIsTheS3Adapter() {
    assertThat(uploadedOriginalStore).isInstanceOf(S3UploadedOriginalStore.class);
  }

  @Test
  void anUploadLandsInTheBucketIsProcessedServedAndRemovedAgain() throws IOException {
    LibraryDocumentEntry uploaded =
        documentService.uploadDocument(
            libraryId,
            new MockMultipartFile(
                "file",
                "dienstanweisung.txt",
                "text/plain",
                "Diese Dienstanweisung regelt den Publikumsverkehr."
                    .getBytes(StandardCharsets.UTF_8)),
            null,
            currentUserOf(editor));
    assertThat(uploaded.document().getStatus()).isEqualTo(DocumentStatus.PENDING);

    Document indexed = awaitDocumentStatus(uploaded.document().getId(), DocumentStatus.INDEXED);
    assertThat(indexed.getFilePath()).startsWith("s3://" + bucket + "/" + libraryId + "/");
    String key = indexed.getFilePath().substring(("s3://" + bucket + "/").length());
    assertThat(objectExists(key)).isTrue();

    // The working file is released by the asynchronous processing on its way out (ADR-0030,
    // Entscheidung 2) - a moment after the status flips, so it is awaited rather than asserted.
    await().atMost(10, TimeUnit.SECONDS).until(() -> ownTempFiles().isEmpty());

    DocumentContent content = documentService.loadContent(indexed.getId(), currentUserOf(editor));
    assertThat(content.isStreamed()).isTrue();
    try (InputStream stream = content.stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("Diese Dienstanweisung regelt den Publikumsverkehr.");
    }

    documentService.deleteDocument(libraryId, indexed.getId(), currentUserOf(editor));

    assertThat(documentRepository.findById(indexed.getId())).isEmpty();
    assertThat(objectExists(key)).isFalse();
  }

  @Test
  void aRejectedDuplicateUploadNeverPutsASecondObject() {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "doppelt.txt", "text/plain", "Zweimal dieselbe Datei.".getBytes());
    LibraryDocumentEntry first =
        documentService.uploadDocument(libraryId, file, null, currentUserOf(editor));
    awaitDocumentStatus(first.document().getId(), DocumentStatus.INDEXED);
    int objectsBefore = objectCount();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> documentService.uploadDocument(libraryId, file, null, currentUserOf(editor)))
        .isInstanceOf(io.opaa.common.ConflictException.class);

    assertThat(objectCount()).isEqualTo(objectsBefore);
    await().atMost(10, TimeUnit.SECONDS).until(() -> ownTempFiles().isEmpty());
  }

  @Test
  void anAttachmentOfAnUploadedMailIsReExtractedFromTheStreamedOriginal() throws Exception {
    // The download path of the re-extraction (ADR-0022) meets a streamed UPLOAD original for the
    // first time here: the root is buffered from the bucket, not opened as a path.
    LibraryDocumentEntry uploaded =
        documentService.uploadDocument(
            libraryId,
            emlFile("anfrage.eml", "Bitte pruefen.", "Anhangsinhalt fuer den Bauantrag."),
            null,
            currentUserOf(editor));
    Document mail = awaitDocumentStatus(uploaded.document().getId(), DocumentStatus.INDEXED);
    List<Document> children = documentRepository.findByParentDocumentId(mail.getId());
    assertThat(children).hasSize(1);
    Document attachment = children.getFirst();
    assertThat(attachment.getFilePath()).startsWith(mail.getFilePath() + "/");

    DocumentContent content =
        documentService.loadContent(attachment.getId(), currentUserOf(editor));
    assertThat(content.isStreamed()).isTrue();
    assertThat(content.fileName()).isEqualTo("anlage.txt");
    try (InputStream stream = content.stream()) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("Anhangsinhalt fuer den Bauantrag.");
    }
    await().atMost(10, TimeUnit.SECONDS).until(() -> ownTempFiles().isEmpty());

    // The attachment row is deletable through the same endpoint; its synthetic locator names no
    // object, so the mail's original stays.
    String mailKey = mail.getFilePath().substring(("s3://" + bucket + "/").length());
    documentService.deleteDocument(libraryId, attachment.getId(), currentUserOf(editor));
    assertThat(objectExists(mailKey)).isTrue();
  }

  private static MockMultipartFile emlFile(String fileName, String bodyText, String attachmentText)
      throws Exception {
    org.apache.james.mime4j.dom.Message message =
        org.apache.james.mime4j.dom.Message.Builder.of()
            .setSubject("Test")
            .setFrom("a@example.org")
            .setTo("b@example.org")
            .setBody(
                org.apache.james.mime4j.message.MultipartBuilder.create("mixed")
                    .addTextPart(bodyText, StandardCharsets.UTF_8)
                    .addBodyPart(
                        org.apache.james.mime4j.message.BodyPartBuilder.create()
                            .setBody(attachmentText.getBytes(StandardCharsets.UTF_8), "text/plain")
                            .setContentDisposition("attachment", "anlage.txt"))
                    .build())
            .build();
    return new MockMultipartFile(
        "file",
        fileName,
        "message/rfc822",
        org.apache.james.mime4j.message.DefaultMessageWriter.asBytes(message));
  }

  @Test
  void theStoreContributesToItsOwnHealthGroupAndNotToTheOverallStatus() {
    SystemHealthDescriptor overall = (SystemHealthDescriptor) healthEndpoint.health();
    assertThat(overall.getComponents()).doesNotContainKey(UploadStoreHealthGroup.CONTRIBUTOR);
    assertThat(overall.getGroups()).contains(UploadStoreHealthGroup.GROUP);

    CompositeHealthDescriptor group =
        (CompositeHealthDescriptor) healthEndpoint.healthForPath(UploadStoreHealthGroup.GROUP);
    assertThat(group.getStatus()).isEqualTo(Status.UP);
    assertThat(group.getComponents()).containsOnlyKeys(UploadStoreHealthGroup.CONTRIBUTOR);
  }

  private CurrentUser currentUserOf(User user) {
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        SystemRole.USER,
        user.getDisplayName(),
        user.getEmail());
  }

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

  private static boolean objectExists(String key) {
    try {
      minio.admin().headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    }
  }

  private static int objectCount() {
    return minio.admin().listObjectsV2(b -> b.bucket(bucket)).contents().size();
  }

  private static List<Path> ownTempFiles() {
    try (Stream<Path> entries = Files.list(tempDir)) {
      return entries
          .filter(
              p -> p.getFileName().toString().startsWith(S3UploadedOriginalStore.TEMP_FILE_PREFIX))
          .toList();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
