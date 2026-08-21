package io.opaa.library;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.DocumentSourceType;
import io.opaa.indexing.FilesystemPathAllowlist;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.RssFeedStateRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit-level coverage of #636, item 1: {@link KnowledgeLibraryService#deleteLibrary}'s
 * connector-library bulk cleanup must delete the document rows before the vector store chunks, not
 * the reverse - mirrors {@link KnowledgeLibraryServiceDeleteLockTest}'s pattern of mocking every
 * dependency to keep this fast and independent of a real Postgres/Testcontainers context.
 *
 * <p>The reverse order (chunks first, then rows - the state before this fix) leaves a window: a
 * concurrently RUNNING indexing job for the same library can write new chunks and have its
 * conditional status-transition UPDATE ({@code DocumentRepository#markIndexedFromSource}, #632)
 * still find the row - because {@code deleteLibrary} had not deleted it yet - after which those
 * freshly-written chunks are never caught by the already-completed bulk chunk delete and survive as
 * orphans. Deleting the rows first closes the window (see the reasoning in {@code deleteLibrary}
 * itself): any conditional UPDATE racing against this method's own row deletion either sees the row
 * already gone (and self-cleans its own chunks per #632) or blocks on the row lock until this
 * method commits and then sees the row gone too - there is no interleaving left where a chunk write
 * can land after the bulk chunk delete already ran while the row still appeared to exist to the
 * writer.
 *
 * <p>The chunk delete is also deferred to after commit (#636 review round 2, item 3) - {@link
 * #vectorStoreDeleteRunsOnlyAfterTheEnclosingTransactionCommits} and {@link
 * #aRolledBackDeleteNeverTouchesTheVectorStoreAtAll} drive {@link
 * TransactionSynchronizationManager} directly, the same way a real {@code @Transactional} AOP proxy
 * would, without needing a Testcontainers-backed transaction manager.
 */
class KnowledgeLibraryServiceConnectorDeleteOrderTest {

  private KnowledgeLibraryService libraryService;
  private DocumentRepository documentRepository;
  private VectorStore vectorStore;
  private UUID ownerId;
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    KnowledgeLibraryRepository libraryRepository = mock(KnowledgeLibraryRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    GroupRepository groupRepository = mock(GroupRepository.class);
    GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
    documentRepository = mock(DocumentRepository.class);
    AssetGrantRepository grantRepository = mock(AssetGrantRepository.class);
    AssetGrantService grantService = mock(AssetGrantService.class);
    when(grantRepository.findByLibraryId(any())).thenReturn(List.of());
    LibraryAccessService accessService = mock(LibraryAccessService.class);
    PermissionHistoryService permissionHistoryService = mock(PermissionHistoryService.class);
    AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
    vectorStore = mock(VectorStore.class);
    FilesystemPathAllowlist filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    IndexingJobRepository indexingJobRepository = mock(IndexingJobRepository.class);
    when(indexingJobRepository.existsByStatusAndLibraryIdAndOrganizationId(
            eq(JobStatus.RUNNING), any(), any()))
        .thenReturn(false);
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
    UUID organizationId = UUID.randomUUID();
    User owner = new User("subject", "issuer", "owner@example.com", "Owner");
    owner.setOrganizationId(organizationId);
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));

    library =
        KnowledgeLibrary.ownedByUser(
            organizationId,
            "Konnektor-Bibliothek",
            null,
            ownerId,
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            "/tmp/does-not-matter",
            null,
            null,
            null,
            false);
    when(libraryRepository.findById(library.getId())).thenReturn(Optional.of(library));
    when(accessService.requireRole(library, ownerId, false, AssetRole.OWNER))
        .thenReturn(AssetRole.OWNER);
    // A non-empty connector library - the branch deleteLibrary reorders (#636).
    when(documentRepository.countByLibraryId(library.getId())).thenReturn(3L);
    when(documentRepository.deleteByLibraryId(library.getId())).thenReturn(3L);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void deleteLibraryDeletesDocumentRowsBeforeVectorStoreChunksForANonEmptyConnectorLibrary() {
    // No active transaction synchronization here (no @Transactional AOP proxy in a plain unit
    // test) - deleteAfterCommit's fallback runs its cleanup immediately, so the row-then-chunk
    // order is still observable synchronously.
    libraryService.deleteLibrary(library.getId(), ownerId, false);

    InOrder order = inOrder(documentRepository, vectorStore);
    order.verify(documentRepository).deleteByLibraryId(library.getId());
    order.verify(vectorStore).delete("library_id == '" + library.getId() + "'");
  }

  @Test
  void vectorStoreDeleteRunsOnlyAfterTheEnclosingTransactionCommits() {
    // #636 review round 2, item 3: drives TransactionSynchronizationManager directly to simulate
    // the @Transactional AOP proxy deleteLibrary actually runs under in production.
    TransactionSynchronizationManager.initSynchronization();

    libraryService.deleteLibrary(library.getId(), ownerId, false);

    verify(documentRepository).deleteByLibraryId(library.getId());
    // The row delete already ran, but the chunk delete must not have - it is only registered to
    // run once this transaction actually commits.
    verify(vectorStore, never()).delete(anyString());

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(synchronization -> synchronization.afterCommit());

    verify(vectorStore).delete("library_id == '" + library.getId() + "'");
  }

  @Test
  void aRolledBackDeleteNeverTouchesTheVectorStoreAtAll() {
    // The scenario the fix exists for: had the chunk delete run eagerly (the pre-#636 behaviour),
    // a rollback after it would leave the document rows behind (rolled back too) with no backing
    // chunks - permanently unfindable, since the next indexing run's checksum-based dedup would
    // skip them as unchanged. Simulated here by never invoking afterCommit at all, the same way a
    // real rollback never would.
    TransactionSynchronizationManager.initSynchronization();

    libraryService.deleteLibrary(library.getId(), ownerId, false);
    TransactionSynchronizationManager.clearSynchronization();

    verify(vectorStore, never()).delete(anyString());
  }
}
