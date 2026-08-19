package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FilesystemPathAllowlist;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * {@link KnowledgeLibraryService#ensurePersonalLibrary}'s race handling is delegated to {@link
 * KnowledgeLibraryRepository#insertPersonalLibraryIfAbsent}'s {@code ON CONFLICT ... DO NOTHING}
 * (#201/#305 code review) - the library-side counterpart of {@code SpaceServiceTest}, which this
 * class deliberately mirrors after the same change there. What remains testable at the unit level
 * is the one decision {@link KnowledgeLibraryService#ensurePersonalLibrary} itself still makes:
 * skip the insert attempt entirely when {@code existsByOwnerUserIdAndPersonalTrue} already reports
 * a personal library, otherwise delegate to the repository.
 */
class KnowledgeLibraryServiceTest {

  private KnowledgeLibraryRepository libraryRepository;
  private PlatformTransactionManager transactionManager;
  private KnowledgeLibraryService libraryService;

  private AssetGrantRepository grantRepository;
  private PermissionHistoryService permissionHistoryService;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    GroupRepository groupRepository = mock(GroupRepository.class);
    GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    grantRepository = mock(AssetGrantRepository.class);
    LibraryAccessService accessService = mock(LibraryAccessService.class);
    permissionHistoryService = mock(PermissionHistoryService.class);
    AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
    VectorStore vectorStore = mock(VectorStore.class);
    transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    // Not exercised by any test in this class - only ensurePersonalLibrary is under test here,
    // which never reaches the FILESYSTEM allowlist check (see
    // KnowledgeLibraryServiceIntegrationTest for that).
    FilesystemPathAllowlist filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    libraryService =
        new KnowledgeLibraryService(
            libraryRepository,
            userRepository,
            groupRepository,
            membershipResolver,
            documentRepository,
            grantRepository,
            accessService,
            permissionHistoryService,
            auditEventRecorder,
            vectorStore,
            transactionManager,
            filesystemAllowlist);
  }

  @Test
  void ensurePersonalLibraryInsertsWhenNoPersonalLibraryExistsYet() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)).thenReturn(false);

    libraryService.ensurePersonalLibrary(userId, organizationId);

    verify(libraryRepository)
        .insertPersonalLibraryIfAbsent(
            any(UUID.class), eq(organizationId), any(String.class), any(String.class), eq(userId));
    // #202: the owner grant is inserted in the same call, on the same connection, right after the
    // library insert - see AssetGrantRepository#insertOwnerGrantForPersonalLibraryIfAbsent.
    verify(grantRepository).insertOwnerGrantForPersonalLibraryIfAbsent(any(UUID.class), eq(userId));
  }

  @Test
  void ensurePersonalLibraryIsANoOpWhenAPersonalLibraryAlreadyExists() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)).thenReturn(true);

    libraryService.ensurePersonalLibrary(userId, organizationId);

    verify(libraryRepository, never())
        .insertPersonalLibraryIfAbsent(
            any(UUID.class),
            any(UUID.class),
            any(String.class),
            any(String.class),
            any(UUID.class));
  }

  @Test
  void ensurePersonalLibraryPropagatesAnyFailureFromTheInsert() {
    // A genuine failure other than the partial-unique-index conflict (which the repository method
    // itself absorbs via ON CONFLICT ... DO NOTHING and therefore never throws for) - e.g. a
    // dangling ownerUserId - must still surface to the caller, not be swallowed.
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)).thenReturn(false);
    DataIntegrityViolationException violation =
        new DataIntegrityViolationException("fk_knowledge_libraries_owner_user violation");
    doThrow(violation)
        .when(libraryRepository)
        .insertPersonalLibraryIfAbsent(
            any(UUID.class), eq(organizationId), any(String.class), any(String.class), eq(userId));

    assertThatThrownBy(() -> libraryService.ensurePersonalLibrary(userId, organizationId))
        .isSameAs(violation);
  }

  @Test
  void ensurePersonalLibraryDoesNotThrowWhenTheInsertSucceedsOrIsANoOp() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)).thenReturn(false);

    assertThatCode(() -> libraryService.ensurePersonalLibrary(userId, organizationId))
        .doesNotThrowAnyException();
  }

  @Test
  void ensurePersonalLibraryHistorisesTheLibraryAndItsOwnerGrantWhenItsOwnInsertActuallyRan() {
    // #238 code review, finding 2: the native ON CONFLICT ... DO NOTHING inserts must still
    // historise the library and its owner grant - previously they never did, a permanent gap for
    // every user provisioned through this path (not merely a pre-#238 backfill gap).
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)).thenReturn(false);
    when(libraryRepository.insertPersonalLibraryIfAbsent(
            any(UUID.class), eq(organizationId), any(String.class), any(String.class), eq(userId)))
        .thenReturn(1);
    when(grantRepository.insertOwnerGrantForPersonalLibraryIfAbsent(any(UUID.class), eq(userId)))
        .thenReturn(1);
    KnowledgeLibrary insertedLibrary =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Meine Dokumente",
            null,
            userId,
            LibraryVisibility.PRIVATE,
            false,
            true);
    AssetGrant insertedGrant =
        AssetGrant.forUser(
            insertedLibrary.getId(), organizationId, userId, AssetRole.OWNER, null, userId);
    when(libraryRepository.findById(any(UUID.class))).thenReturn(Optional.of(insertedLibrary));
    when(grantRepository.findById(any(UUID.class))).thenReturn(Optional.of(insertedGrant));

    libraryService.ensurePersonalLibrary(userId, organizationId);

    verify(permissionHistoryService).recordLibraryCreated(insertedLibrary, userId);
    verify(permissionHistoryService).recordGrantCreated(insertedGrant, userId);
  }

  @Test
  void ensurePersonalLibraryDoesNotHistoriseWhenARaceLoserFindsTheInsertsAlreadyDone() {
    // The ON CONFLICT ... DO NOTHING no-op path (a concurrent caller won the race) must not write
    // a second, conflicting open history interval for the library another caller already
    // historised.
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)).thenReturn(false);
    when(libraryRepository.insertPersonalLibraryIfAbsent(
            any(UUID.class), eq(organizationId), any(String.class), any(String.class), eq(userId)))
        .thenReturn(0);
    when(grantRepository.insertOwnerGrantForPersonalLibraryIfAbsent(any(UUID.class), eq(userId)))
        .thenReturn(0);

    libraryService.ensurePersonalLibrary(userId, organizationId);

    verify(permissionHistoryService, never()).recordLibraryCreated(any(), any());
    verify(permissionHistoryService, never()).recordGrantCreated(any(), any());
    verify(libraryRepository, never()).findById(any());
    verify(grantRepository, never()).findById(any());
  }
}
