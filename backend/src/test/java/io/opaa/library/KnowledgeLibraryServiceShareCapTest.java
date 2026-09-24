package io.opaa.library;

import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetChanged;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetOwnerNames;
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
 * Unit-level coverage of the share cap #797 introduces, in the shape #1931 gave it: the two
 * booleans {@code KnowledgeLibraryService#updateShareCap} sets - may this library be granted to
 * "Alle Konten", may it be listed - and what lowering either of them takes back at once. Wired
 * exactly like {@link KnowledgeLibraryServiceFilesystemAllowlistTest}: a mocked repository that
 * echoes {@code save} back, no Spring context.
 */
class KnowledgeLibraryServiceShareCapTest {

  private KnowledgeLibraryService libraryService;
  private KnowledgeLibraryRepository libraryRepository;
  private AuditEventRecorder auditEventRecorder;
  private ApplicationEventPublisher eventPublisher;
  private AssetGrantService grantService;
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
    grantService = mock(AssetGrantService.class);
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

  private KnowledgeLibrary filesystemLibrary(boolean listed) {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Bibliothek",
            null,
            ownerId,
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

  private KnowledgeLibrary uploadLibrary() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(organizationId, "Uploads", null, ownerId, false);
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    return library;
  }

  // --- updateLibrary is capped -------------------------------------------------------------

  @Test
  void updateLibraryRejectsListedAboveTheShareCapWith409() {
    KnowledgeLibrary library = filesystemLibrary(false);
    library.updateShareCap(true, false);

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.getId(), libraryUpdate("Bibliothek").listed(true).build(), ownerCaller))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("gelistet");
  }

  @Test
  void updateLibraryAllowsListedAtTheShareCap() {
    KnowledgeLibrary library = filesystemLibrary(false);
    library.updateShareCap(true, true);

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.getId(), libraryUpdate("Bibliothek").listed(true).build(), ownerCaller);

    assertThat(updated.library().isListed()).isTrue();
  }

  @Test
  void updateLibraryIgnoresTheShareCapForAnUploadLibrary() {
    KnowledgeLibrary library = uploadLibrary();

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.getId(), libraryUpdate("Uploads").listed(true).build(), ownerCaller);

    assertThat(updated.library().isListed()).isTrue();
  }

  // --- updateShareCap: who may call it --------------------------------------------------

  @Test
  void updateShareCapIsRefusedWithoutSystemAdmin() {
    KnowledgeLibrary library = filesystemLibrary(false);

    assertThatThrownBy(
            () -> libraryService.updateShareCap(library.getId(), false, true, ownerCaller))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void updateShareCapIsRefusedForAnUploadLibrary() {
    KnowledgeLibrary library = uploadLibrary();

    assertThatThrownBy(
            () -> libraryService.updateShareCap(library.getId(), false, true, systemAdminCaller))
        .isInstanceOf(ValidationException.class);
  }

  // --- updateShareCap: what lowering it takes back at once ------------------------------

  /**
   * #1931: organization-wide reach is a grant, so the clamp is a revocation through the ordinary
   * grant path - not a field the shell lowers.
   */
  @Test
  void forbiddingAllAccountsRevokesAnExistingGrantToAllAccounts() {
    KnowledgeLibrary library = filesystemLibrary(false);

    LibraryDetail result =
        libraryService.updateShareCap(library.getId(), false, true, systemAdminCaller);

    assertThat(result.library().isAllAccountsGrantAllowed()).isFalse();
    verify(grantService).revokeAllAccountsGrantForLoweredCap(library, systemAdminCaller.id());
  }

  @Test
  void loweringTheListedCapClearsListedImmediately() {
    KnowledgeLibrary library = filesystemLibrary(true);

    LibraryDetail result =
        libraryService.updateShareCap(library.getId(), true, false, systemAdminCaller);

    assertThat(result.library().isListed()).isFalse();
    assertThat(result.library().isListedCap()).isFalse();
    // the clamp writes the same event an owner's own edit would - one history interval, one
    // audit entry, through the identical publish path
    verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(AssetChanged.class));
  }

  @Test
  void loweringTheShareCapRecordsItsOwnAuditEventSeparatelyFromTheClamp() {
    KnowledgeLibrary library = filesystemLibrary(true);

    libraryService.updateShareCap(library.getId(), false, false, systemAdminCaller);

    org.mockito.ArgumentCaptor<AuditEvent> captor =
        org.mockito.ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserAction(captor.capture());
    assertThat(captor.getValue().eventType())
        .isEqualTo(AuditEventType.CONNECTOR_LIBRARY_SHARE_LIMIT_CHANGED);
  }

  @Test
  void raisingTheShareCapNeverClampsAndPublishesNoVisibilityChangedEvent() {
    KnowledgeLibrary library = filesystemLibrary(false);

    LibraryDetail result =
        libraryService.updateShareCap(library.getId(), true, true, systemAdminCaller);

    assertThat(result.library().isListed()).isFalse();
    assertThat(result.library().isAllAccountsGrantAllowed()).isTrue();
    verify(eventPublisher, never()).publishEvent(any());
    verify(grantService, never()).revokeAllAccountsGrantForLoweredCap(any(), any());
  }
}
