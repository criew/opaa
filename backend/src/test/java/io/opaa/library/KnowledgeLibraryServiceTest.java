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

import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.DocumentRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

  @BeforeEach
  void setUp() {
    libraryRepository = mock(KnowledgeLibraryRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    GroupRepository groupRepository = mock(GroupRepository.class);
    GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
    DocumentRepository documentRepository = mock(DocumentRepository.class);
    transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    libraryService =
        new KnowledgeLibraryService(
            libraryRepository,
            userRepository,
            groupRepository,
            membershipResolver,
            documentRepository,
            transactionManager);
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
}
