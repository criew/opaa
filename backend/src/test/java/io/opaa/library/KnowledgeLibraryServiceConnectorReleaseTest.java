package io.opaa.library;

import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
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
import io.opaa.common.AccessDeniedException;
import io.opaa.connection.ConnectorReleaseService;
import io.opaa.connection.LibraryConnectionService;
import io.opaa.connection.profile.OwnAddressDrafts;
import io.opaa.indexing.chunk.FullTextChunkStore;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.chunk.VectorStoreWriter;
import io.opaa.indexing.credentials.LibrarySourceConnectionResolver;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.registry.TestSourceConnectors;
import io.opaa.indexing.source.confluence.ConfluenceConnectionService;
import io.opaa.indexing.source.rss.RssFeedStateRepository;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.FullSyncRhythmSettingRepository;
import io.opaa.indexing.spi.SourceConnectorStubs;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.knowledge.LibraryFolderRepository;
import io.opaa.knowledge.LibraryStorageQuotaService;
import io.opaa.knowledge.SourceType;
import io.opaa.metadata.EmbeddingRateEstimator;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.PermissionHistoryService;
import io.opaa.sourceaccess.FilesystemPathAllowlist;
import io.opaa.test.SourceTypes;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A stored connector library pointed at another origin is a new target: the person changing it
 * needs the connector release of its type, like for a new library. A change within the same origin
 * keeps the target and asks for nothing.
 */
class KnowledgeLibraryServiceConnectorReleaseTest {

  private static final String STORED_URL = "https://dateien.example.org/akten/";

  private KnowledgeLibraryService libraryService;
  private KnowledgeLibraryRepository libraryRepository;
  private ConnectorReleaseService connectorRelease;
  private FilesystemPathAllowlist filesystemAllowlist;
  private UUID ownerId;
  private CurrentUser ownerCaller;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    when(libraryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    UserRepository userRepository = mock(UserRepository.class);
    GroupSubjectDirectory groupDirectory = mock(GroupSubjectDirectory.class);
    GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    AssetGrantRepository grantRepository = mock(AssetGrantRepository.class);
    when(grantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    AssetGrantService grantService = mock(AssetGrantService.class);
    LibraryAccessService accessService = mock(LibraryAccessService.class);
    when(accessService.effectiveRole(any(), any(), anyBoolean())).thenReturn(AssetRole.OWNER);
    connectorRelease = mock(ConnectorReleaseService.class);
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
            new EmbeddingRateEstimator(4.0),
            2048);
    filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    IndexingJobRepository indexingJobRepository = mock(IndexingJobRepository.class);
    RssFeedStateRepository rssFeedStateRepository = mock(RssFeedStateRepository.class);
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
    LibraryStorageQuotaService storageQuotaService = mock(LibraryStorageQuotaService.class);
    LibraryFolderRepository folderRepository = mock(LibraryFolderRepository.class);
    ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    libraryService =
        new KnowledgeLibraryService(
            mock(AssetSuccessionSource.class),
            libraryRepository,
            new AssetOwnerNames(userRepository, groupDirectory),
            mock(CapabilityService.class),
            documentRepository,
            grantService,
            new AssetShellService(
                new AssetTypes(
                    List.of(new KnowledgeLibraryAssetType(SourceConnectorStubs.registry()))),
                grantService,
                grantRepository,
                mock(AssetOwnershipHistoryService.class),
                permissionHistoryService,
                visibilityHistoryService,
                auditEventRecorder,
                eventPublisher),
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
                    org.mockito.Mockito.mock(io.opaa.indexing.spi.SourceSyncStateRepository.class))
                .confluenceConnectionService(
                    org.mockito.Mockito.mock(ConfluenceConnectionService.class))
                .registry(),
            new LibrarySourceConnectionResolver(),
            mock(LibraryConnectionService.class),
            connectorRelease,
            OwnAddressDrafts.over(TestSourceConnectors.connectors().registry(), libraryRepository),
            mock(io.opaa.asset.OwnerOnlyRule.class),
            new FullSyncRhythm(mock(FullSyncRhythmSettingRepository.class)),
            mock(io.opaa.connection.PrivateLibraryConnections.class));

    ownerId = UUID.randomUUID();
    User owner = new User("subject", "issuer", "owner@example.com", "Owner");
    owner.setOrganizationId(UUID.randomUUID());
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    ownerCaller = CurrentUser.of(ownerId, owner.getOrganizationId(), SystemRole.USER, "Owner");
  }

  private void storeLibrary(SourceType type, String path, String url) {
    library =
        KnowledgeLibrary.ownedByUser(
            ownerCaller.organizationId(), "Akten", null, ownerId, type, path, url, null);
    when(libraryRepository.findByIdAndOrganizationId(library.getId(), ownerCaller.organizationId()))
        .thenReturn(Optional.of(library));
    when(libraryRepository.requireInOrganization(library.getId(), ownerCaller.organizationId()))
        .thenReturn(library);
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    doThrow(new AccessDeniedException("Ihnen fehlt das Anlegerecht", "CAPABILITY_REQUIRED"))
        .when(connectorRelease)
        .requireCreatable(eq(ownerCaller), eq(type), eq(null));
  }

  // regression guard for #3358 (1): a MANAGER without the release must not open a new origin
  @Test
  void aNewOriginNeedsTheConnectorRelease() {
    storeLibrary(SourceTypes.HTTP_DIRECTORY, null, STORED_URL);

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.getId(),
                    libraryUpdate("Akten")
                        .sourceUrl(URI.create("https://fremd.example.net/akten/"))
                        .build(),
                    ownerCaller))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Ihnen fehlt das Anlegerecht");
    assertThat(library.getSourceUrl()).isEqualTo(STORED_URL);
  }

  @Test
  void aNewPathOnTheSameOriginNeedsNoRelease() {
    storeLibrary(SourceTypes.HTTP_DIRECTORY, null, STORED_URL);

    libraryService.updateLibrary(
        library.getId(),
        libraryUpdate("Akten")
            .sourceUrl(URI.create("https://dateien.example.org/akten/2026/"))
            .build(),
        ownerCaller);

    assertThat(library.getSourceUrl()).isEqualTo("https://dateien.example.org/akten/2026/");
    verify(connectorRelease, never()).requireCreatable(any(), any(), any());
  }

  // regression guard for #3358 (1): a directory library's origin is its path
  @Test
  void aNewDirectoryNeedsTheConnectorRelease() {
    when(filesystemAllowlist.isConfigured()).thenReturn(true);
    when(filesystemAllowlist.isAllowed(any())).thenReturn(true);
    storeLibrary(SourceTypes.FILESYSTEM, "/data/akten", null);

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.getId(),
                    libraryUpdate("Akten").sourcePath("/data/personal").build(),
                    ownerCaller))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(library.getSourcePath()).isEqualTo("/data/akten");
  }

  @Test
  void aRenameNeedsNoRelease() {
    storeLibrary(SourceTypes.HTTP_DIRECTORY, null, STORED_URL);

    libraryService.updateLibrary(library.getId(), libraryUpdate("Akten 2026").build(), ownerCaller);

    assertThat(library.getName()).isEqualTo("Akten 2026");
    verify(connectorRelease, never()).requireCreatable(any(), any(), any());
  }
}
