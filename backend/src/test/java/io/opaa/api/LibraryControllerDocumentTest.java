package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.auth.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.DocumentStatus;
import io.opaa.library.AssetGrantService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDocumentService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
  @MockitoBean private UserService userService;

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
  void uploadingADocumentReturns201WithTheResponseFromTheService() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    var response =
        new LibraryDocumentResponse(
            documentId, "report.pdf", DocumentStatus.INDEXED, DocumentSourceType.UPLOAD, 3);
    when(documentService.uploadDocument(eq(libraryId), any(), eq(currentUserId), eq(false)))
        .thenReturn(response);

    var file = new MockMultipartFile("file", "report.pdf", "application/pdf", "content".getBytes());

    mockMvc
        .perform(
            multipart("/api/v1/libraries/" + libraryId + "/documents")
                .file(file)
                .with(asTestUser()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(documentId.toString()))
        .andExpect(jsonPath("$.fileName").value("report.pdf"))
        .andExpect(jsonPath("$.sourceType").value("UPLOAD"));
  }

  @Test
  void uploadingIntoAForbiddenLibraryReturns403() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(documentService.uploadDocument(eq(libraryId), any(), eq(currentUserId), eq(false)))
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
