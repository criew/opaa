package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.dto.LibraryRequest;
import io.opaa.api.dto.LibraryResponse;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.RssFeedStateRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit-level coverage of {@link KnowledgeLibraryService}'s FILESYSTEM allowlist enforcement (#484,
 * ADR-0018 Entscheidung 6) - specifically the "no allowlist configured at all" branch, which {@link
 * KnowledgeLibraryServiceIntegrationTest} cannot exercise itself without spinning up a second,
 * distinct Spring context (the shared integration test context's dev-profile allowlist is fixed at
 * {@code /data,/tmp} for the whole suite, see application.yml). Mocking {@link
 * FilesystemPathAllowlist} directly keeps this fast and avoids that extra context entirely.
 */
class KnowledgeLibraryServiceFilesystemAllowlistTest {

  private KnowledgeLibraryService libraryService;
  private FilesystemPathAllowlist filesystemAllowlist;
  private UUID ownerId;

  @BeforeEach
  void setUp() {
    KnowledgeLibraryRepository libraryRepository = mock(KnowledgeLibraryRepository.class);
    when(libraryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    UserRepository userRepository = mock(UserRepository.class);
    GroupRepository groupRepository = mock(GroupRepository.class);
    GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    AssetGrantRepository grantRepository = mock(AssetGrantRepository.class);
    when(grantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AssetGrantService grantService = mock(AssetGrantService.class);
    LibraryAccessService accessService = mock(LibraryAccessService.class);
    PermissionHistoryService permissionHistoryService = mock(PermissionHistoryService.class);
    AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
    VectorStore vectorStore = mock(VectorStore.class);
    filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    IndexingJobRepository indexingJobRepository = mock(IndexingJobRepository.class);
    RssFeedStateRepository rssFeedStateRepository = mock(RssFeedStateRepository.class);
    LibraryStorageQuotaService storageQuotaService = mock(LibraryStorageQuotaService.class);

    libraryService =
        new KnowledgeLibraryService(
            libraryRepository,
            userRepository,
            groupRepository,
            membershipResolver,
            documentRepository,
            grantRepository,
            grantService,
            accessService,
            permissionHistoryService,
            auditEventRecorder,
            vectorStore,
            filesystemAllowlist,
            indexingJobRepository,
            rssFeedStateRepository,
            storageQuotaService);

    ownerId = UUID.randomUUID();
    User owner = new User("subject", "issuer", "owner@example.com", "Owner");
    owner.setOrganizationId(UUID.randomUUID());
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
  }

  @Test
  void createLibraryRejectsFilesystemSourceTypeWhenNoAllowlistIsConfigured() {
    when(filesystemAllowlist.isConfigured()).thenReturn(false);
    LibraryRequest request =
        new LibraryRequest("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents");

    assertThatThrownBy(() -> libraryService.createLibrary(request, ownerId))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createLibraryStillAcceptsUploadSourceTypeWhenNoAllowlistIsConfigured() {
    // The allowlist gates FILESYSTEM specifically - an operator who has configured none must not
    // accidentally lose every source type.
    when(filesystemAllowlist.isConfigured()).thenReturn(false);
    LibraryRequest request = new LibraryRequest("Uploads", DocumentSourceType.UPLOAD);

    LibraryResponse response = libraryService.createLibrary(request, ownerId);

    assertThat(response.getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
  }

  @Test
  void createLibraryRejectsFilesystemSourceTypeWithAPathOutsideAConfiguredAllowlist() {
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed("/etc/shadow")).thenReturn(false);
    LibraryRequest request =
        new LibraryRequest("Verzeichnis", DocumentSourceType.FILESYSTEM).sourcePath("/etc/shadow");

    assertThatThrownBy(() -> libraryService.createLibrary(request, ownerId))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void createLibraryAcceptsFilesystemSourceTypeWithAPathInsideTheAllowlist() {
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed("/srv/opaa/documents")).thenReturn(true);
    LibraryRequest request =
        new LibraryRequest("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/srv/opaa/documents");

    LibraryResponse response = libraryService.createLibrary(request, ownerId);

    assertThat(response.getSourceType()).isEqualTo(DocumentSourceType.FILESYSTEM);
    assertThat(response.getSourcePath()).isEqualTo("/srv/opaa/documents");
  }
}
