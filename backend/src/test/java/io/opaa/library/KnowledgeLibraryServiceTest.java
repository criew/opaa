package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
 * Simulates the two concurrent first-login race #265 describes, without relying on real threads or
 * timing - the library-side counterpart of {@code SpaceServiceTest}, which this class deliberately
 * mirrors: {@link KnowledgeLibraryRepository#existsByOwnerUserIdAndPersonalTrue} is stubbed to
 * answer as it would for the loser of the race (false before the attempt, true after a concurrent
 * winner has committed), and {@link KnowledgeLibraryRepository#saveAndFlush} is stubbed to throw
 * the {@link DataIntegrityViolationException} that {@code uk_knowledge_libraries_personal_owner}
 * (migration 012) would raise for the losing insert.
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
  void ensurePersonalLibraryReadsTheWinnersLibraryInsteadOfThrowing() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    when(libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)).thenReturn(false, true);
    when(libraryRepository.saveAndFlush(any(KnowledgeLibrary.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key value violates unique constraint"
                    + " \"uk_knowledge_libraries_personal_owner\""));

    assertThatCode(() -> libraryService.ensurePersonalLibrary(userId, organizationId))
        .doesNotThrowAnyException();

    verify(libraryRepository, times(2)).existsByOwnerUserIdAndPersonalTrue(userId);
  }

  @Test
  void ensurePersonalLibraryPropagatesViolationsUnrelatedToTheRace() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    when(libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)).thenReturn(false, false);
    DataIntegrityViolationException violation =
        new DataIntegrityViolationException("some other constraint violation");
    when(libraryRepository.saveAndFlush(any(KnowledgeLibrary.class))).thenThrow(violation);

    assertThatThrownBy(() -> libraryService.ensurePersonalLibrary(userId, organizationId))
        .isSameAs(violation);
  }

  @Test
  void ensurePersonalLibraryIsANoOpWhenAPersonalLibraryAlreadyExists() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(libraryRepository.existsByOwnerUserIdAndPersonalTrue(userId)).thenReturn(true);

    libraryService.ensurePersonalLibrary(userId, organizationId);

    verify(libraryRepository, times(0)).saveAndFlush(any(KnowledgeLibrary.class));
  }
}
