package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FullTextChunkStore;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.IndexingJobService;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.VectorStoreWriter;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.indexing.source.rss.RssFeedStateRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit-level coverage of the #433 delete lock: {@link KnowledgeLibraryService#deleteLibrary}
 * rejects deleting a library with a {@link JobStatus#RUNNING} indexing job, mirroring the pattern
 * {@link KnowledgeLibraryServiceFilesystemAllowlistTest} uses to keep pure decision logic fast and
 * independent of a real Postgres/Testcontainers context. {@link
 * KnowledgeLibraryServiceIntegrationTest#cannotDeleteALibraryWhileAnIndexingRunIsRunningButCanOnceItFinishes}
 * covers the same behaviour against the real schema.
 */
class KnowledgeLibraryServiceDeleteLockTest {

  private KnowledgeLibraryService libraryService;
  private KnowledgeLibraryRepository libraryRepository;
  private LibraryAccessService accessService;
  private IndexingJobRepository indexingJobRepository;
  private UUID ownerId;
  private CurrentUser ownerCaller;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    GroupRepository groupRepository = mock(GroupRepository.class);
    GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    AssetGrantRepository grantRepository = mock(AssetGrantRepository.class);
    AssetGrantService grantService = mock(AssetGrantService.class);
    when(grantRepository.findByLibraryId(any())).thenReturn(List.of());
    accessService = mock(LibraryAccessService.class);
    PermissionHistoryService permissionHistoryService = mock(PermissionHistoryService.class);
    AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
    VectorChunkStore vectorChunkStore =
        new VectorChunkStore(
            mock(VectorStore.class),
            mock(org.springframework.ai.embedding.EmbeddingModel.class),
            mock(org.springframework.ai.embedding.BatchingStrategy.class),
            mock(VectorStoreWriter.class),
            mock(FullTextChunkStore.class));
    FilesystemPathAllowlist filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    indexingJobRepository = mock(IndexingJobRepository.class);
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
    UUID organizationId = UUID.randomUUID();
    User owner = new User("subject", "issuer", "owner@example.com", "Owner");
    owner.setOrganizationId(organizationId);
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    ownerCaller = CurrentUser.of(ownerId, organizationId, SystemRole.USER, "Owner");

    library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Laufende Indizierung",
            null,
            ownerId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.UPLOAD,
            null,
            null,
            null,
            null,
            false);
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(accessService.requireRole(library, ownerId, false, AssetRole.OWNER))
        .thenReturn(AssetRole.OWNER);
    when(documentRepository.countByLibraryId(library.getId())).thenReturn(0L);
  }

  @Test
  void deleteLibraryRejectsWithConflictWhileAnIndexingRunIsRunning() {
    when(indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
            JobStatus.RUNNING, library.getId(), library.getOrganizationId()))
        .thenReturn(true);

    assertThatThrownBy(() -> libraryService.deleteLibrary(library.getId(), ownerCaller))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            ex -> {
              assertThat(ex.getMessage()).contains("indiziert");
            });
  }

  @Test
  void deleteLibrarySucceedsWithoutARunningIndexingJob() {
    when(indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
            JobStatus.RUNNING, library.getId(), library.getOrganizationId()))
        .thenReturn(false);

    libraryService.deleteLibrary(library.getId(), ownerCaller);
  }
}
