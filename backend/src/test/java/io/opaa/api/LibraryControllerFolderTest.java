package io.opaa.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.dto.LibraryFolderRenameRequest;
import io.opaa.api.dto.LibraryFolderResponse;
import io.opaa.auth.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.library.AssetGrantService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDocumentService;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.SourceConnectionTestService;
import io.opaa.space.SpaceAssetAssociationService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-level wiring test for the four folder endpoints #820 adds (review finding on PR #827:
 * the issue's acceptance criteria call for service <em>and</em> web tests, mirroring {@code
 * LibraryControllerDocumentTest}). Verifies that {@link LibraryController}'s folder methods
 * translate an HTTP request into the correct {@link LibraryFolderService} call and its result - or
 * thrown {@link ResponseStatusException} - into the expected status code, that the generated DTOs'
 * {@code @Valid} constraints ({@code LibraryFolderRequest.name}'s {@code minLength}/{@code
 * maxLength}) are actually enforced at this layer, and that the {@code systemAdmin} flag reaches
 * the service unchanged. Business logic (permissions, name/depth/conflict validation, recursive
 * deletion) is covered at the service level in {@code LibraryFolderServiceTest}/{@code
 * LibraryFolderServiceIntegrationTest}.
 */
@WebMvcTest(LibraryController.class)
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class LibraryControllerFolderTest {

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

  private LibraryFolderResponse sampleResponse(UUID libraryId, UUID folderId, String name) {
    return new LibraryFolderResponse(folderId, libraryId, name, 0L, Instant.now());
  }

  @Test
  void creatingAFolderReturns201WithTheResponseFromTheService() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    when(folderService.createFolder(
            eq(libraryId),
            org.mockito.ArgumentMatchers.argThat(request -> "Protokolle".equals(request.getName())),
            eq(currentUserId),
            eq(false)))
        .thenReturn(sampleResponse(libraryId, folderId, "Protokolle"));

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/folders")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Protokolle\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(folderId.toString()))
        .andExpect(jsonPath("$.libraryId").value(libraryId.toString()))
        .andExpect(jsonPath("$.name").value("Protokolle"));
  }

  @Test
  void creatingAFolderWithABlankNameIsRejectedWith400BeforeReachingTheService() throws Exception {
    // The generated LibraryFolderRequest.name carries @NotNull @Size(min = 1, max = 255) - this
    // proves @Valid on LibraryController#createFolder actually rejects an empty name at the web
    // layer, never even calling the (mocked) service.
    UUID libraryId = UUID.randomUUID();

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/folders")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void creatingAFolderWithANameOverTheLengthLimitIsRejectedWith400() throws Exception {
    UUID libraryId = UUID.randomUUID();
    String tooLong = "x".repeat(256);

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/folders")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void creatingAFolderInAForbiddenLibraryReturns403() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(folderService.createFolder(
            eq(libraryId), org.mockito.ArgumentMatchers.any(), eq(currentUserId), eq(false)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Kein Zugriff auf diese Bibliothek"));

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/folders")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Protokolle\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void creatingADuplicateNamedFolderReturns409() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(folderService.createFolder(
            eq(libraryId), org.mockito.ArgumentMatchers.any(), eq(currentUserId), eq(false)))
        .thenThrow(
            new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene"));

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/folders")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Protokolle\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void gettingAFolderReturns200WithTheDocumentCount() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    when(folderService.getFolder(libraryId, folderId, currentUserId, false))
        .thenReturn(sampleResponse(libraryId, folderId, "Archiv").documentCount(3L));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/folders/" + folderId).with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(folderId.toString()))
        .andExpect(jsonPath("$.documentCount").value(3));
  }

  @Test
  void gettingAnUnknownFolderReturns404() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    when(folderService.getFolder(libraryId, folderId, currentUserId, false))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Ordner nicht gefunden"));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/folders/" + folderId).with(asTestUser()))
        .andExpect(status().isNotFound());
  }

  @Test
  void renamingAFolderReturns200WithTheUpdatedName() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    when(folderService.renameFolder(
            eq(libraryId),
            eq(folderId),
            org.mockito.ArgumentMatchers.argThat(
                (LibraryFolderRenameRequest request) -> "Neu".equals(request.getName())),
            eq(currentUserId),
            eq(false)))
        .thenReturn(sampleResponse(libraryId, folderId, "Neu"));

    mockMvc
        .perform(
            patch("/api/v1/libraries/" + libraryId + "/folders/" + folderId)
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Neu\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Neu"));
  }

  @Test
  void renamingAFolderWithABlankNameIsRejectedWith400() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();

    mockMvc
        .perform(
            patch("/api/v1/libraries/" + libraryId + "/folders/" + folderId)
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void deletingAFolderReturns204() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();

    mockMvc
        .perform(
            delete("/api/v1/libraries/" + libraryId + "/folders/" + folderId).with(asTestUser()))
        .andExpect(status().isNoContent());

    verify(folderService).deleteFolder(libraryId, folderId, currentUserId, false);
  }

  @Test
  void deletingAFolderInAConnectorLibraryReturns409() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(
            new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell"
                    + " verwalteten Ordner"))
        .when(folderService)
        .deleteFolder(libraryId, folderId, currentUserId, false);

    mockMvc
        .perform(
            delete("/api/v1/libraries/" + libraryId + "/folders/" + folderId).with(asTestUser()))
        .andExpect(status().isConflict());
  }

  @Test
  void aSystemAdminsRequestsAreForwardedWithTheSystemAdminFlagSet() throws Exception {
    // LibraryController#currentUser resolves SystemRole.SYSTEM_ADMIN from the authenticated user,
    // not from the request - this proves that flag actually reaches LibraryFolderService, the same
    // fail-open-for-admins wiring every other library endpoint already relies on.
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    User admin = new User("admin-subject", TEST_ISSUER, "admin@example.com", "Admin");
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    UUID adminId = UUID.randomUUID();
    setId(admin, adminId);
    when(userService.findBySubjectAndIssuer("admin-subject", TEST_ISSUER))
        .thenReturn(Optional.of(admin));
    when(folderService.getFolder(libraryId, folderId, adminId, true))
        .thenReturn(sampleResponse(libraryId, folderId, "Archiv"));

    mockMvc
        .perform(
            get("/api/v1/libraries/" + libraryId + "/folders/" + folderId)
                .with(
                    jwt()
                        .jwt(
                            builder -> builder.subject("admin-subject").claim("iss", TEST_ISSUER))))
        .andExpect(status().isOk());

    verify(folderService).getFolder(libraryId, folderId, adminId, true);
  }
}
