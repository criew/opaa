package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetOwnerNames;
import io.opaa.asset.AssetShellService;
import io.opaa.asset.AssetSuccessionSource;
import io.opaa.asset.AssetTypes;
import io.opaa.asset.AssetVisibilityHistoryService;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ValidationException;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.chunk.EmbeddingRateEstimator;
import io.opaa.indexing.chunk.FullTextChunkStore;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.chunk.VectorStoreWriter;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.indexing.source.confluence.ConfluenceConnectionService;
import io.opaa.indexing.source.confluence.ConfluenceProperties;
import io.opaa.indexing.source.rss.RssFeedStateRepository;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.LibraryFolderRepository;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.PermissionHistoryService;
import io.opaa.permission.SuccessionReachGuard;
import java.time.Clock;
import java.util.List;
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
    GroupSubjectDirectory groupDirectory = mock(GroupSubjectDirectory.class);
    GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    AssetGrantRepository grantRepository = mock(AssetGrantRepository.class);
    when(grantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AssetGrantService grantService = mock(AssetGrantService.class);
    LibraryAccessService accessService = mock(LibraryAccessService.class);
    PermissionHistoryService permissionHistoryService = mock(PermissionHistoryService.class);
    AssetVisibilityHistoryService visibilityHistoryService =
        mock(AssetVisibilityHistoryService.class);
    AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
    VectorChunkStore vectorChunkStore =
        new VectorChunkStore(
            mock(VectorStore.class),
            mock(org.springframework.ai.embedding.EmbeddingModel.class),
            mock(org.springframework.ai.embedding.BatchingStrategy.class),
            mock(VectorStoreWriter.class),
            mock(FullTextChunkStore.class),
            new EmbeddingRateEstimator(4.0));
    filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    IndexingJobRepository indexingJobRepository = mock(IndexingJobRepository.class);
    RssFeedStateRepository rssFeedStateRepository = mock(RssFeedStateRepository.class);
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
    LibraryStorageQuotaService storageQuotaService = mock(LibraryStorageQuotaService.class);
    LibraryFolderRepository folderRepository = mock(LibraryFolderRepository.class);
    ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    // #1200: every null/zero component falls back to the record's own defaults (7-day rhythm).
    ConfluenceProperties confluenceProperties =
        new ConfluenceProperties(0, null, null, 0, null, 0, 0, 0, null, null, 0);
    libraryService =
        new KnowledgeLibraryService(
            mock(AssetSuccessionSource.class),
            libraryRepository,
            new AssetOwnerNames(userRepository, groupDirectory),
            mock(CapabilityService.class),
            documentRepository,
            grantService,
            new AssetShellService(
                new AssetTypes(List.of(new KnowledgeLibraryAssetType())),
                grantService,
                grantRepository,
                mock(AssetOwnershipHistoryService.class),
                permissionHistoryService,
                visibilityHistoryService,
                auditEventRecorder,
                eventPublisher,
                mock(SuccessionReachGuard.class)),
            accessService,
            auditEventRecorder,
            vectorChunkStore,
            indexingJobRepository,
            indexingJobService,
            Clock.systemDefaultZone(),
            storageQuotaService,
            mock(LibraryExternalAccessService.class),
            folderRepository,
            eventPublisher,
            TestSourceConnectors.connectors()
                .filesystemAllowlist(filesystemAllowlist)
                .rssFeedStateRepository(rssFeedStateRepository)
                .sourceSyncStateRepository(
                    org.mockito.Mockito.mock(
                        io.opaa.indexing.source.SourceSyncStateRepository.class))
                .confluenceConnectionService(
                    org.mockito.Mockito.mock(ConfluenceConnectionService.class))
                .confluenceProperties(confluenceProperties)
                .registry());

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
