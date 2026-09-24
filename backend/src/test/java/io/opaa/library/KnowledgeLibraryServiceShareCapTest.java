package io.opaa.library;

import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetChanged;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetShellService;
import io.opaa.asset.AssetSuccessionSource;
import io.opaa.asset.AssetTypes;
import io.opaa.asset.AssetVisibilityHistoryService;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.chunk.EmbeddingRateEstimator;
import io.opaa.indexing.chunk.FullTextChunkStore;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.chunk.VectorStoreWriter;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.source.confluence.ConfluenceProperties;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.indexing.source.rss.RssFeedStateRepository;
import io.opaa.indexing.source.s3.S3ClientFactory;
import io.opaa.indexing.source.s3.S3Properties;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.PermissionHistoryService;
import io.opaa.permission.SuccessionReachGuard;
import io.opaa.sourceaccess.TargetAddressValidator;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit-level coverage of the share cap #797 introduces - the ceiling {@code
 * KnowledgeLibraryService#updateShareCap} sets on {@code visibility}/{@code listed} and {@code
 * updateLibrary}'s own refusal once a request would exceed it. Wired exactly like {@link
 * KnowledgeLibraryServiceFilesystemAllowlistTest}: a mocked repository that echoes {@code save}
 * back, no Spring context.
 */
class KnowledgeLibraryServiceShareCapTest {

  private KnowledgeLibraryService libraryService;
  private KnowledgeLibraryRepository libraryRepository;
  private AuditEventRecorder auditEventRecorder;
  private ApplicationEventPublisher eventPublisher;
  private UUID ownerId;
  private UUID organizationId;
  private CurrentUser ownerCaller;
  private CurrentUser systemAdminCaller;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    when(libraryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    UserRepository userRepository = mock(UserRepository.class);
    GroupSubjectDirectory groupDirectory = mock(GroupSubjectDirectory.class);
    GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    AssetGrantRepository grantRepository = mock(AssetGrantRepository.class);
    AssetGrantService grantService = mock(AssetGrantService.class);
    LibraryAccessService accessService = mock(LibraryAccessService.class);
    PermissionHistoryService permissionHistoryService = mock(PermissionHistoryService.class);
    AssetVisibilityHistoryService visibilityHistoryService =
        mock(AssetVisibilityHistoryService.class);
    auditEventRecorder = mock(AuditEventRecorder.class);
    VectorChunkStore vectorChunkStore =
        new VectorChunkStore(
            mock(VectorStore.class),
            mock(org.springframework.ai.embedding.EmbeddingModel.class),
            mock(org.springframework.ai.embedding.BatchingStrategy.class),
            mock(VectorStoreWriter.class),
            mock(FullTextChunkStore.class),
            new EmbeddingRateEstimator(4.0));
    FilesystemPathAllowlist filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    IndexingJobRepository indexingJobRepository = mock(IndexingJobRepository.class);
    RssFeedStateRepository rssFeedStateRepository = mock(RssFeedStateRepository.class);
    IndexingJobService indexingJobService = mock(IndexingJobService.class);
    LibraryStorageQuotaService storageQuotaService = mock(LibraryStorageQuotaService.class);
    LibraryFolderRepository folderRepository = mock(LibraryFolderRepository.class);
    eventPublisher = mock(ApplicationEventPublisher.class);

    ConfluenceProperties confluenceProperties =
        new ConfluenceProperties(0, null, null, 0, null, 0, 0, 0, null, null, 0);
    libraryService =
        new KnowledgeLibraryService(
            mock(AssetSuccessionSource.class),
            libraryRepository,
            userRepository,
            groupDirectory,
            membershipResolver,
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
            filesystemAllowlist,
            indexingJobRepository,
            indexingJobService,
            rssFeedStateRepository,
            mock(io.opaa.indexing.source.SourceSyncStateRepository.class),
            Clock.systemDefaultZone(),
            storageQuotaService,
            mock(LibraryExternalAccessService.class),
            folderRepository,
            eventPublisher,
            mock(ConfluenceConnectionService.class),
            confluenceProperties,
            new S3ClientFactory(S3Properties.defaults(), TargetAddressValidator.disabled()));

    organizationId = UUID.randomUUID();
    ownerId = UUID.randomUUID();
    User owner = new User("subject", "issuer", "owner@example.com", "Owner");
    owner.setOrganizationId(organizationId);
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
    ownerCaller = CurrentUser.of(ownerId, organizationId, SystemRole.USER, "Owner");
    systemAdminCaller =
        CurrentUser.of(UUID.randomUUID(), organizationId, SystemRole.SYSTEM_ADMIN, "Admin");
    // #797: updateLibrary's own MANAGER bar is not this class's subject - every library here is
    // reached through the owner's own OWNER role, which effectiveRole/requireRole both grant.
    when(accessService.requireRole(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
        .thenAnswer(invocation -> io.opaa.api.types.AssetRole.OWNER);
    when(accessService.effectiveRole(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(io.opaa.api.types.AssetRole.OWNER);
  }

  private KnowledgeLibrary filesystemLibrary(AssetVisibility visibility, boolean listed) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            ownerId,
            visibility,
            listed,
            DocumentSourceType.FILESYSTEM,
            "/data/dokumente",
            null,
            null,
            null,
            false);
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    return library;
  }

  // --- updateLibrary is capped -------------------------------------------------------------

  @Test
  void updateLibraryRejectsVisibilityAboveTheShareCapWith409() {
    KnowledgeLibrary library = filesystemLibrary(AssetVisibility.PRIVATE, false);
    library.updateShareCap(AssetVisibility.SHARED, true);

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.getId(),
                    libraryUpdate("Bibliothek").visibility(AssetVisibility.ORGANIZATION).build(),
                    ownerCaller))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("geteilt");
  }

  @Test
  void updateLibraryRejectsListedAboveTheShareCapWith409() {
    KnowledgeLibrary library = filesystemLibrary(AssetVisibility.SHARED, false);
    library.updateShareCap(AssetVisibility.SHARED, false);

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.getId(), libraryUpdate("Bibliothek").listed(true).build(), ownerCaller))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("gelistet");
  }

  @Test
  void updateLibraryAllowsVisibilityAtTheShareCap() {
    KnowledgeLibrary library = filesystemLibrary(AssetVisibility.PRIVATE, false);
    library.updateShareCap(AssetVisibility.SHARED, true);

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.getId(),
            libraryUpdate("Bibliothek").visibility(AssetVisibility.SHARED).build(),
            ownerCaller);

    assertThat(updated.library().getVisibility()).isEqualTo(AssetVisibility.SHARED);
  }

  @Test
  void updateLibraryIgnoresTheShareCapForAnUploadLibrary() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId, "Uploads", null, ownerId, AssetVisibility.PRIVATE, false);
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.getId(),
            libraryUpdate("Uploads").visibility(AssetVisibility.ORGANIZATION).build(),
            ownerCaller);

    assertThat(updated.library().getVisibility()).isEqualTo(AssetVisibility.ORGANIZATION);
  }

  // --- updateShareCap: who may call it --------------------------------------------------

  @Test
  void updateShareCapIsRefusedWithoutSystemAdmin() {
    KnowledgeLibrary library = filesystemLibrary(AssetVisibility.PRIVATE, false);

    assertThatThrownBy(
            () ->
                libraryService.updateShareCap(
                    library.getId(), AssetVisibility.SHARED, true, ownerCaller))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void updateShareCapIsRefusedForAnUploadLibrary() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId, "Uploads", null, ownerId, AssetVisibility.PRIVATE, false);
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));

    assertThatThrownBy(
            () ->
                libraryService.updateShareCap(
                    library.getId(), AssetVisibility.PRIVATE, true, systemAdminCaller))
        .isInstanceOf(ValidationException.class);
  }

  // --- updateShareCap: the immediate clamp --------------------------------------------------

  @Test
  void loweringTheShareCapClampsAWiderVisibilityAndListedImmediately() {
    KnowledgeLibrary library = filesystemLibrary(AssetVisibility.ORGANIZATION, true);

    LibraryDetail result =
        libraryService.updateShareCap(
            library.getId(), AssetVisibility.PRIVATE, false, systemAdminCaller);

    assertThat(result.library().getVisibility()).isEqualTo(AssetVisibility.PRIVATE);
    assertThat(result.library().isListed()).isFalse();
    assertThat(result.library().getVisibilityCap()).isEqualTo(AssetVisibility.PRIVATE);
    assertThat(result.library().isListedCap()).isFalse();
    // the clamp writes the same event an owner's own edit would - one history interval, one
    // audit entry, through the identical publish path
    verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(AssetChanged.class));
  }

  @Test
  void loweringTheShareCapRecordsItsOwnAuditEventSeparatelyFromTheClamp() {
    KnowledgeLibrary library = filesystemLibrary(AssetVisibility.ORGANIZATION, true);

    libraryService.updateShareCap(
        library.getId(), AssetVisibility.PRIVATE, false, systemAdminCaller);

    org.mockito.ArgumentCaptor<AuditEvent> captor =
        org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(captor.capture());
    assertThat(captor.getValue().eventType())
        .isEqualTo(AuditEventType.CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED);
  }

  /**
   * #1870 review, "Zweig ohne Test": every other clamp test above lowers both fields together -
   * this one lowers only {@code listedCap}, {@code visibilityCap} stays at its wide default, so
   * only {@code listedClamped} (not {@code visibilityClamped}) is true in {@code updateShareCap}.
   */
  @Test
  void loweringOnlyTheListedCapClampsListedAloneAndLeavesVisibilityUntouched() {
    KnowledgeLibrary library = filesystemLibrary(AssetVisibility.ORGANIZATION, true);

    LibraryDetail result =
        libraryService.updateShareCap(
            library.getId(), AssetVisibility.ORGANIZATION, false, systemAdminCaller);

    assertThat(result.library().getVisibility()).isEqualTo(AssetVisibility.ORGANIZATION);
    assertThat(result.library().isListed()).isFalse();
    assertThat(result.library().isListedCap()).isFalse();
    verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(AssetChanged.class));
  }

  @Test
  void raisingTheShareCapNeverClampsAndPublishesNoVisibilityChangedEvent() {
    KnowledgeLibrary library = filesystemLibrary(AssetVisibility.PRIVATE, false);

    LibraryDetail result =
        libraryService.updateShareCap(
            library.getId(), AssetVisibility.ORGANIZATION, true, systemAdminCaller);

    assertThat(result.library().getVisibility()).isEqualTo(AssetVisibility.PRIVATE);
    assertThat(result.library().isListed()).isFalse();
    verify(eventPublisher, never()).publishEvent(any());
  }
}
