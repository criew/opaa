package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    GroupRepository groupRepository = mock(GroupRepository.class);
    GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    AssetGrantRepository grantRepository = mock(AssetGrantRepository.class);
    when(grantRepository.findByLibraryId(any())).thenReturn(List.of());
    AssetGrantService grantService = mock(AssetGrantService.class);
    accessService = mock(LibraryAccessService.class);
    PermissionHistoryService permissionHistoryService = mock(PermissionHistoryService.class);
    AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
    VectorStore vectorStore = mock(VectorStore.class);
    FilesystemPathAllowlist filesystemAllowlist = mock(FilesystemPathAllowlist.class);
    indexingJobRepository = mock(IndexingJobRepository.class);

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
            indexingJobRepository);

    ownerId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    User owner = new User("subject", "issuer", "owner@example.com", "Owner");
    owner.setOrganizationId(organizationId);
    when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));

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
    when(indexingJobRepository.existsByStatusAndLibraryId(JobStatus.RUNNING, library.getId()))
        .thenReturn(true);

    assertThatThrownBy(() -> libraryService.deleteLibrary(library.getId(), ownerId, false))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex -> {
              ResponseStatusException responseStatusException = (ResponseStatusException) ex;
              assertThat(responseStatusException.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(responseStatusException.getReason()).contains("indiziert");
            });
  }

  @Test
  void deleteLibrarySucceedsWithoutARunningIndexingJob() {
    when(indexingJobRepository.existsByStatusAndLibraryId(JobStatus.RUNNING, library.getId()))
        .thenReturn(false);

    libraryService.deleteLibrary(library.getId(), ownerId, false);
  }
}
