package io.opaa.library.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.FeatureFlags;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.indexing.source.DocumentIndexingService;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryDocumentPage;
import io.opaa.library.LibraryDocumentService;
import io.opaa.library.SourceConnectionTestService;
import io.opaa.permission.PermissionTransferService;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.succession.SuccessionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With the metadata feature off (ADR-0044) the document list ignores the Pflege-Anker's filter
 * {@code missingMetadataField}: the request answers the plain list instead of narrowing it.
 */
@WebMvcTest(
    controllers = LibraryController.class,
    properties = "opaa.features.metadata.enabled=false")
@ActiveProfiles({"test", "dev"})
@Import({TestSecurityConfig.class, FeatureFlags.class})
class LibraryControllerMetadataSwitchedOffTest {

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
  @MockitoBean private io.opaa.library.PrivateLibraryCreation privateCreation;
  @MockitoBean private io.opaa.library.PrivateLibraryErasure privateErasure;
  @MockitoBean private SourceConnectorRegistry sourceConnectorRegistry;
  @MockitoBean private SpaceAssetAssociationService associationService;
  @MockitoBean private PermissionTransferService transferService;
  @MockitoBean private SuccessionService successionService;

  @BeforeEach
  void setUp() {
    User user = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    user.setSystemRole(SystemRole.USER);
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && TEST_SUBJECT.equals(token.getSubject()))))
        .thenReturn(user);
  }

  @Test
  void theMaintenanceFilterIsIgnored() throws Exception {
    UUID libraryId = UUID.randomUUID();
    when(libraryService.listDocuments(eq(libraryId), any(), isNull(), isNull(), isNull(), any()))
        .thenReturn(new LibraryDocumentPage(List.of(), 0, 20, 7L, List.of(), List.of(), null));

    mockMvc
        .perform(
            get("/api/v1/libraries/" + libraryId + "/documents")
                .param("missingMetadataField", "document_date")
                .with(
                    jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(7));
  }
}
