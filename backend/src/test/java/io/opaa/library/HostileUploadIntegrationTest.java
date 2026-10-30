package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.opaa.api.types.DocumentStatus;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.format.hostile.HostileFiles;
import io.opaa.format.hostile.HostileFiles.HostileFile;
import io.opaa.indexing.attachment.StandaloneAttachmentAccess;
import io.opaa.indexing.document.DocumentIngest;
import io.opaa.indexing.document.DocumentIngestService;
import io.opaa.instance.CurrentInstance;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrantHistoryRepository;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Crafted files of {@link HostileFiles} on the processing of an upload - the upload pool, the
 * routing by detected content, the format with the production limits, the attachments and the
 * bookkeeping of the row: each one ends within a fixed time, a file a limit refuses as {@code
 * FAILED} with the German message of the upload list, and a regular file uploaded right after it is
 * indexed. The suite's upload limit is a few kilobytes, so a crafted file enters behind the size
 * check, the way {@link LibraryDocumentService#uploadDocument} hands an accepted file over. The
 * guards of the parser fixes themselves, with bounds that turn a reverted fix red, are in {@code
 * FormatHostileInputTest}; a case here guards a fix only where the row would end otherwise.
 */
@OpaaIntegrationTest
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class HostileUploadIntegrationTest {

  private static final Duration PROCESSING_BOUND = Duration.ofSeconds(90);

  @Autowired private LibraryDocumentService documentService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private DocumentIngestService documentIngestService;
  @Autowired private UploadedOriginalStore uploadedOriginalStore;
  @Autowired private CurrentInstance currentInstance;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private User editor;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
    editor = new User("hostile-editor", "issuer", "hostile@example.com", "Editor");
    editor.setOrganizationId(organizationId);
    editor = userRepository.save(editor);
    libraryId =
        libraryService
            .createLibrary(
                libraryCreation("Präparierte Dateien", SourceType.UPLOAD).build(), caller())
            .library()
            .getId();
  }

  @AfterEach
  void tearDown() {
    ownLibraryFixtures.removeLibraries(libraryId);
    grantHistoryRepository.deleteBySubjectUserIdIn(List.of(editor.getId()));
    membershipHistoryRepository.deleteByUserIdIn(List.of(editor.getId()));
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organizationId);
    userRepository.deleteById(editor.getId());
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void aPdfWithACyclicOutlineIsIndexedAndTheNextUploadToo() throws IOException {
    Document pdf = processed(HostileFiles.pdfWithCyclicOutline());

    assertThat(pdf.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertTheNextUploadIsIndexed("nach-gliederung.txt");
  }

  // regression guard for #3336: an XML part unpacking past the part limit fails before the DOM
  @Test
  void aDocxCompressionBombFailsWithAMessageAndTheNextUploadIsIndexed() throws IOException {
    Document docx = processed(HostileFiles.docxCompressionBomb(40L * 1024 * 1024));

    assertFailedWithTheUploadListMessage(docx);
    assertTheNextUploadIsIndexed("nach-bombe.txt");
  }

  @Test
  void messagesNestedPastTheDepthLimitAreCutOffAndTheNextUploadIsIndexed() throws IOException {
    Document mail = processed(HostileFiles.emlWithNestedMessages(8, 2));

    assertThat(mail.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    List<String> attachments =
        documentRepository.findByLibraryId(libraryId).stream()
            .filter(document -> document.getParentDocumentId() != null)
            .map(Document::getFileName)
            .toList();
    // opaa.indexing.attachments.max-depth is 5: the fifth nested message is indexed, not its own
    assertThat(attachments)
        .contains("ebene-5.eml", "anlage-4-1.txt")
        .doesNotContain("ebene-6.eml", "anlage-5-0.txt");
    assertTheNextUploadIsIndexed("nach-anhaengen.txt");
  }

  @Test
  void anOutlookMessageSharingAttachmentSectorsStaysWithinItsSize() throws IOException {
    HostileFile file = HostileFiles.msgWithSharedAttachmentSectors(64 * 1024, 30, 512);

    Document message = processed(file);

    long unpacked =
        documentRepository.findByParentDocumentId(message.getId()).stream()
            .mapToLong(
                attachment -> attachment.getFileSize() == null ? 0 : attachment.getFileSize())
            .sum();
    assertThat(message.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertThat(unpacked).isLessThanOrEqualTo(file.bytes().length);
    assertTheNextUploadIsIndexed("nach-nachricht.txt");
  }

  @Test
  void anHtmlTitleThatMadeTheUrlCleanupBacktrackIsIndexed() throws IOException {
    Document html = processed(HostileFiles.htmlWithBacktrackingTitle(33_000));

    assertThat(html.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertTheNextUploadIsIndexed("nach-titel.txt");
  }

  @Test
  void aMarkdownHeadingThatMadeThePatternBacktrackIsIndexed() throws IOException {
    Document markdown = processed(HostileFiles.markdownWithBacktrackingHeading(200_000));

    assertThat(markdown.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertTheNextUploadIsIndexed("nach-ueberschrift.txt");
  }

  @Test
  void deeplyNestedWideTablesAreIndexed() throws IOException {
    Document html = processed(HostileFiles.htmlWithNestedTables(120, 1_000));

    assertThat(html.getStatus()).isEqualTo(DocumentStatus.INDEXED);
    assertTheNextUploadIsIndexed("nach-tabellen.txt");
  }

  /**
   * Hands {@code file} to the upload pool as an accepted upload and waits for its row to leave
   * {@code PENDING}, at most {@link #PROCESSING_BOUND}.
   */
  private Document processed(HostileFile file) throws IOException {
    KnowledgeLibrary library = libraryRepository.findById(libraryId).orElseThrow();
    String extension = file.fileName().substring(file.fileName().lastIndexOf('.'));
    UploadedOriginalStore.AcceptedUpload accepted =
        uploadedOriginalStore.accept(
            organizationId, libraryId, extension, new ByteArrayInputStream(file.bytes()));
    Document row =
        new Document(
            file.fileName(),
            accepted.store().locator(),
            "application/octet-stream",
            (long) file.bytes().length,
            library.getSourceType());
    row.setLibraryId(libraryId);
    row.setOrganizationId(organizationId);
    row.setUploadedByUserId(editor.getId());
    row.setProcessingInstanceId(currentInstance.id());
    row = documentRepository.save(row);
    documentIngestService.processUploadedFileAsync(
        DocumentIngest.builder(library)
            .file(accepted.workingFile(), file.bytes().length)
            .filePath(row.getFilePath())
            .fileName(file.fileName())
            .sourceType(library.getSourceType())
            .existingRow()
            .malwareScanned()
            .build(),
        new StandaloneAttachmentAccess(library, "Upload"),
        accepted::release);
    return settled(row.getId());
  }

  private void assertTheNextUploadIsIndexed(String fileName) {
    HostileFile regular = HostileFiles.regularText(fileName);
    LibraryDocumentEntry entry =
        documentService.uploadDocument(
            libraryId,
            new MockMultipartFile("file", fileName, "text/plain", regular.bytes()),
            null,
            caller());

    assertThat(settled(entry.document().getId()).getStatus()).isEqualTo(DocumentStatus.INDEXED);
  }

  private static void assertFailedWithTheUploadListMessage(Document document) {
    assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
    assertThat(document.getErrorMessage()).isEqualTo("Die Datei konnte nicht verarbeitet werden");
  }

  private Document settled(UUID documentId) {
    await()
        .atMost(PROCESSING_BOUND)
        .until(
            () ->
                documentRepository
                    .findById(documentId)
                    .map(Document::getStatus)
                    .filter(status -> status != DocumentStatus.PENDING)
                    .isPresent());
    return documentRepository.findById(documentId).orElseThrow();
  }

  private CurrentUser caller() {
    return CurrentUser.of(
        editor.getId(),
        organizationId,
        editor.getSystemRole(),
        editor.getDisplayName(),
        editor.getEmail());
  }
}
