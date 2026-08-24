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

import io.opaa.auth.CurrentUser;
import io.opaa.auth.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.indexing.DocumentIndexingService;
import io.opaa.library.AssetGrantService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDocumentService;
import io.opaa.library.LibraryFolder;
import io.opaa.library.LibraryFolderDetail;
import io.opaa.library.LibraryFolderService;
import io.opaa.library.SourceConnectionTestService;
import io.opaa.space.SpaceAssetAssociationService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Controller-level wiring test for the four folder endpoints #820 adds (review finding on PR #827:
 * the issue's acceptance criteria call for service <em>and</em> web tests, mirroring {@code
 * LibraryControllerDocumentTest}). Verifies that {@link LibraryController}'s folder methods
 * translate an HTTP request into the correct {@link LibraryFolderService} call and its result - or
 * a thrown {@code io.opaa.common} domain exception - into the expected status code, that the
 * generated DTOs' {@code @Valid} constraints ({@code LibraryFolderRequest.name}'s {@code
 * minLength}/{@code maxLength}) are actually enforced at this layer, and that the {@code
 * systemAdmin} flag reaches the service unchanged. Business logic (permissions, name/depth/conflict
 * validation, recursive deletion) is covered at the service level in {@code
 * LibraryFolderServiceTest}/{@code LibraryFolderServiceIntegrationTest}.
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
  private CurrentUser caller;

  @BeforeEach
  void setUp() {
    User user = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    user.setSystemRole(SystemRole.USER);
    setId(user, currentUserId);
    caller =
        new CurrentUser(
            user.getId(), user.getOrganizationId(), user.getSystemRole(), user.getDisplayName());
    when(userService.findOrCreateUser(
            org.mockito.ArgumentMatchers.eq(TEST_SUBJECT),
            org.mockito.ArgumentMatchers.eq(TEST_ISSUER),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(user);
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

  private LibraryFolderDetail sampleDetail(UUID libraryId, UUID folderId, String name) {
    return sampleDetail(libraryId, folderId, name, 0L);
  }

  private LibraryFolderDetail sampleDetail(
      UUID libraryId, UUID folderId, String name, long documentCount) {
    LibraryFolder folder = new LibraryFolder(libraryId, null, name, UUID.randomUUID());
    setId(folder, folderId);
    return new LibraryFolderDetail(folder, documentCount);
  }

  private void setId(LibraryFolder folder, UUID id) {
    try {
      var field = LibraryFolder.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(folder, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void creatingAFolderReturns201WithTheResponseFromTheService() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    when(folderService.createFolder(
            eq(libraryId), eq("Protokolle"), org.mockito.ArgumentMatchers.isNull(), eq(caller)))
        .thenReturn(sampleDetail(libraryId, folderId, "Protokolle"));

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
  void creatingAFolderWithAParentFolderIdForwardsItToTheService() throws Exception {
    // #872 review: request.getParentFolderId() unpacking at the LibraryController call site had no
    // test forcing a non-null value through - a null literal there would have stayed green.
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    UUID parentFolderId = UUID.randomUUID();
    when(folderService.createFolder(eq(libraryId), eq("2026"), eq(parentFolderId), eq(caller)))
        .thenReturn(sampleDetail(libraryId, folderId, "2026"));

    mockMvc
        .perform(
            post("/api/v1/libraries/" + libraryId + "/folders")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"2026\",\"parentFolderId\":\"" + parentFolderId + "\"}"))
        .andExpect(status().isCreated());

    verify(folderService).createFolder(libraryId, "2026", parentFolderId, caller);
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
            eq(libraryId),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            eq(caller)))
        .thenThrow(new AccessDeniedException("Kein Zugriff auf diese Bibliothek"));

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
            eq(libraryId),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            eq(caller)))
        .thenThrow(
            new ConflictException(
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
    when(folderService.getFolder(libraryId, folderId, caller))
        .thenReturn(sampleDetail(libraryId, folderId, "Archiv", 3L));

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
    when(folderService.getFolder(libraryId, folderId, caller))
        .thenThrow(new NotFoundException("Ordner nicht gefunden"));

    mockMvc
        .perform(get("/api/v1/libraries/" + libraryId + "/folders/" + folderId).with(asTestUser()))
        .andExpect(status().isNotFound());
  }

  @Test
  void renamingAFolderReturns200WithTheUpdatedName() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    when(folderService.renameFolder(eq(libraryId), eq(folderId), eq("Neu"), eq(caller)))
        .thenReturn(sampleDetail(libraryId, folderId, "Neu"));

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

    verify(folderService).deleteFolder(libraryId, folderId, caller);
  }

  @Test
  void deletingAFolderInAConnectorLibraryReturns409() throws Exception {
    UUID libraryId = UUID.randomUUID();
    UUID folderId = UUID.randomUUID();
    org.mockito.Mockito.doThrow(
            new ConflictException(
                "Diese Bibliothek ist eine Konnektorbibliothek und unterstützt keine manuell"
                    + " verwalteten Ordner"))
        .when(folderService)
        .deleteFolder(libraryId, folderId, caller);

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
    CurrentUser adminCaller =
        new CurrentUser(
            admin.getId(),
            admin.getOrganizationId(),
            admin.getSystemRole(),
            admin.getDisplayName());
    when(userService.findOrCreateUser(
            org.mockito.ArgumentMatchers.eq("admin-subject"),
            org.mockito.ArgumentMatchers.eq(TEST_ISSUER),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(admin);
    when(folderService.getFolder(libraryId, folderId, adminCaller))
        .thenReturn(sampleDetail(libraryId, folderId, "Archiv"));

    mockMvc
        .perform(
            get("/api/v1/libraries/" + libraryId + "/folders/" + folderId)
                .with(
                    jwt()
                        .jwt(
                            builder -> builder.subject("admin-subject").claim("iss", TEST_ISSUER))))
        .andExpect(status().isOk());

    verify(folderService).getFolder(libraryId, folderId, adminCaller);
  }
}
