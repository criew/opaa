package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.FullTextChunkStore;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.RssFeedStateRepository;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.VectorStoreWriter;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

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
  private CurrentUser ownerCaller;

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
    VectorChunkStore vectorChunkStore =
        new VectorChunkStore(
            mock(VectorStore.class),
            mock(org.springframework.ai.embedding.EmbeddingModel.class),
            mock(org.springframework.ai.embedding.BatchingStrategy.class),
            mock(VectorStoreWriter.class),
            mock(FullTextChunkStore.class));
    filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    IndexingJobRepository indexingJobRepository = mock(IndexingJobRepository.class);
    RssFeedStateRepository rssFeedStateRepository = mock(RssFeedStateRepository.class);
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
    LibraryStorageQuotaService storageQuotaService = mock(LibraryStorageQuotaService.class);
    LibraryFolderRepository folderRepository = mock(LibraryFolderRepository.class);
    ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

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
            vectorChunkStore,
            filesystemAllowlist,
            indexingJobRepository,
            indexingJobService,
            rssFeedStateRepository,
            Clock.systemDefaultZone(),
            storageQuotaService,
            folderRepository,
            eventPublisher);

    ownerId = UUID.randomUUID();
    User owner = new User("subject", "issuer", "owner@example.com", "Owner");
    owner.setOrganizationId(UUID.randomUUID());
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    ownerCaller = CurrentUser.of(ownerId, owner.getOrganizationId(), SystemRole.USER, "Owner");
  }

  @Test
  void createLibraryRejectsFilesystemSourceTypeWhenNoAllowlistIsConfigured() {
    when(filesystemAllowlist.isConfigured()).thenReturn(false);
    LibraryCreation request =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents")
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, ownerCaller))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryStillAcceptsUploadSourceTypeWhenNoAllowlistIsConfigured() {
    // The allowlist gates FILESYSTEM specifically - an operator who has configured none must not
    // accidentally lose every source type.
    when(filesystemAllowlist.isConfigured()).thenReturn(false);
    LibraryCreation request = libraryCreation("Uploads", DocumentSourceType.UPLOAD).build();

    LibraryDetail response = libraryService.createLibrary(request, ownerCaller);

    assertThat(response.library().getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
  }

  @Test
  void createLibraryRejectsFilesystemSourceTypeWithAPathOutsideAConfiguredAllowlist() {
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed("/etc/shadow")).thenReturn(false);
    LibraryCreation request =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/etc/shadow")
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, ownerCaller))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryAcceptsFilesystemSourceTypeWithAPathInsideTheAllowlist() {
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed("/srv/opaa/documents")).thenReturn(true);
    LibraryCreation request =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/srv/opaa/documents")
            .build();

    LibraryDetail response = libraryService.createLibrary(request, ownerCaller);

    assertThat(response.library().getSourceType()).isEqualTo(DocumentSourceType.FILESYSTEM);
    assertThat(response.managementDetail().sourcePath()).isEqualTo("/srv/opaa/documents");
  }
}
