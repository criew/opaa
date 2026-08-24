package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.DocumentStatus;
import io.opaa.library.AssetGrantService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDocumentEntry;
import io.opaa.library.LibraryDocumentPage;
import io.opaa.library.LibraryDocumentService;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.SourceConnectionTestService;
import io.opaa.space.SpaceAssetAssociationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-level wiring test for the two endpoints #420 adds: uploadDocument/deleteDocument
 * correctly translate the HTTP request into a {@link LibraryDocumentService} call and its result
 * into the expected status code. Business logic (permissions, format/size/dedup validation, path
 * traversal) is covered at the service level in {@code LibraryDocumentServiceTest}.
 */
@WebMvcTest(LibraryController.class)
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class LibraryControllerDocumentTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private KnowledgeLibraryService libraryService;
  @MockitoBean private AssetGrantService grantService;
  @MockitoBean private LibraryDocumentService documentService;
  @MockitoBean private LibraryFolderService folderService;
  @MockitoBean private DocumentIndexingService indexingService;
  @MockitoBean private UserService userService;
  @MockitoBean private SourceConnectionTestService sourceConnectionTestService;
  @MockitoBean private SpaceAssetAssociationService associationService;

  private final UUID currentUserId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    User user = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    user.setSystemRole(SystemRole.USER);
    setId(user, currentUserId);
    when(userService.findBySubjectAndIssuer(TEST_SUBJECT, TEST_ISSUER))
        .thenReturn(Optional.of(user));
  }

  private RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  private void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void listingDocumentsPassesPageSizeAndQToTheServiceWithAStableSort() throws Exception {
    UUID libraryId = UUID.randomUUID();
    var response = new LibraryDocumentPage(List.of(), 1, 5, 12L, List.of(), List.of(), null);
    when(libraryService.listDocuments(
            eq(libraryId),
            eq(currentUserId),
            eq(false),
            eq("dienst"),
            any(),
            argThat(
                (Pageable p) ->
                    p.getPageNumber() == 1
                        && p.getPageSize() == 5
                        // #517 code review, finding 1: LIMIT/OFFSET without a stable ORDER BY has
                        // no guaranteed row order across separate requests in PostgreSQL.
                        && p.getSort()
                            .equals(Sort.by(Sort.Order.asc("fileName"), Sort.Order.asc("id"))))))
        .thenReturn(response);

    mockMvc
        .perform(
            get("/api/v1/libraries/" + libraryId + "/documents")
                .param("page", "1")
                .param("size", "5")
                .param("q", "dienst")
                .with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(5))
        .andExpect(jsonPath("$.totalElements").value(12));
  }

  @Test
  void listingDocumentsPassesFolderIdToTheService() throws Exception {
    // #821: folderId is forwarded to the service as-is, distinct from the eq(null) default the
    // no-param case above implicitly covers via any().
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    var response = new LibraryDocumentPage(List.of(), 0, 20, 0L, List.of(), List.of(), folderId);
    when(libraryService.listDocuments(
            eq(libraryId), eq(currentUserId), eq(false), isNull(), eq(folderId), any()))
        .thenReturn(response);

    mockMvc
        .perform(
            get("/api/v1/libraries/" + libraryId + "/documents")
                .param("folderId", folderId.toString())
                .with(asTestUser()))
        .andExpect(status().isOk());
  }

  @Test
  void listingDocumentsRejectsAnOutOfRangeSizeWith400() throws Exception {
    // #517 code review, finding 2: the spec promises size in 1..100 - silently clamping an
    // out-of-range value would contradict that, so it is rejected instead (see
    // LibraryController#listDocuments).
    UUID libraryId = UUID.randomUUID();

    mockMvc
        .perform(
            get("/api/v1/libraries/" + libraryId + "/documents")
                .param("size", "500")
                .with(asTestUser()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/v1/libraries/" + libraryId + "/documents")
                .param("size", "0")
                .with(asTestUser()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listingDocumentsRejectsANegativePageWith400() throws Exception {
    UUID libraryId = UUID.randomUUID();

    mockMvc
        .perform(
            get("/api/v1/libraries/" + libraryId + "/documents")
                .param("page", "-1")
                .with(asTestUser()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void uploadingADocumentReturns201WithTheResponseFromTheService() throws Exception {
    UUID libraryId = UUID.randomUUID();
    Document document =
        new Document(
            "report.pdf", "/tmp/report.pdf", "application/pdf", 3L, DocumentSourceType.UPLOAD);
    document.setStatus(DocumentStatus.INDEXED);
    document.setChunkCount(3);
    var response = new LibraryDocumentEntry(document, null);
    // #823: the controller now calls LibraryDocumentService's 6-arg folderPath overload
    // unconditionally (folderPath is simply null/omitted when the request does not send one).
    when(documentService.uploadDocument(
            eq(libraryId), any(), any(), any(), eq(currentUserId), eq(false)))
        .thenReturn(response);

    var file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/libraries/" + libraryId + "/documents")
                .file(file)
                .with(asTestUser()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(document.getId().toString()))
        .andExpect(jsonPath("$.fileName").value("report.pdf"))
        .andExpect(jsonPath("$.sourceType").value("UPLOAD"));
  }

  @Test
  void uploadingADocumentPassesFolderIdToTheService() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    Document document =
        new Document(
            "report.pdf", "/tmp/report.pdf", "application/pdf", 3L, DocumentSourceType.UPLOAD);
    document.setStatus(DocumentStatus.INDEXED);
    document.setChunkCount(3);
    document.setFolderId(folderId);
    var response = new LibraryDocumentEntry(document, "Protokolle");
    when(documentService.uploadDocument(
            eq(libraryId), any(), eq(folderId), any(), eq(currentUserId), eq(false)))
        .thenReturn(response);

    var file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/libraries/" + libraryId + "/documents")
                .file(file)
                .param("folderId", folderId.toString())
                .with(asTestUser()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.folderId").value(folderId.toString()))
        .andExpect(jsonPath("$.folderPath").value("Protokolle"));
  }

  @Test
  void uploadingADocumentPassesFolderPathToTheService() throws Exception {
    // #823: folderPath is forwarded to the service alongside folderId, letting a
    // dragged-and-dropped/webkitdirectory-selected folder tree materialize its structure.
    UUID libraryId = UUID.randomUUID();
    Document document =
        new Document(
            "januar.pdf", "/tmp/januar.pdf", "application/pdf", 0L, DocumentSourceType.UPLOAD);
    document.setStatus(DocumentStatus.PENDING);
    var response = new LibraryDocumentEntry(document, "Protokolle/2026");
    when(documentService.uploadDocument(
            eq(libraryId), any(), isNull(), eq("Protokolle/2026"), eq(currentUserId), eq(false)))
        .thenReturn(response);

    var file = new MockMultipartFile("file", "januar.pdf", "application/pdf", "content".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/libraries/" + libraryId + "/documents")
                .file(file)
                .param("folderPath", "Protokolle/2026")
                .with(asTestUser()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.folderPath").value("Protokolle/2026"));
  }

  @Test
  void uploadingIntoAForbiddenLibraryReturns403() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(documentService.uploadDocument(
            eq(libraryId), any(), any(), any(), eq(currentUserId), eq(false)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek"));

    var file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/libraries/" + libraryId + "/documents")
                .file(file)
                .with(asTestUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void uploadingWithoutTheFilePartReturns400WithAGermanMessage() throws Exception {
    // #420 code review, finding 2: without GlobalExceptionHandler#handleMissingServletRequestPart
    // Exception, this reached handleGenericException and answered 500.
    UUID libraryId = UUID.randomUUID();

    mockMvc
        .perform(multipart("/api/v1/libraries/" + libraryId + "/documents").with(asTestUser()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Der Anfrageteil 'file' fehlt"));
  }

  @Test
  void deletingADocumentReturns204() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();

    mockMvc
        .perform(
            delete("/api/v1/libraries/" + libraryId + "/documents/" + documentId)
                .with(asTestUser()))
        .andExpect(status().isNoContent());
  }
}
