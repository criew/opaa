package io.opaa.space;

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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * {@link SpaceService#ensurePersonalSpace}'s race handling (#265) is now entirely delegated to
 * {@link SpaceRepository#insertPersonalSpaceIfAbsent}'s {@code ON CONFLICT ... DO NOTHING} (#201/
 * #305 code review) - there is no more application-level catch-and-reread branch to simulate here,
 * unlike the version of this test that predates that change. What remains testable at the unit
 * level is the one decision {@link SpaceService#ensurePersonalSpace} itself still makes: skip the
 * insert attempt entirely when {@code existsByOwnerIdAndKind} already reports a personal space,
 * otherwise delegate to the repository. A {@link PlatformTransactionManager} is still mocked here
 * (not a real one) because {@link SpaceService} constructs its own {@link
 * org.springframework.transaction.support.TransactionTemplate} from it in the constructor; {@code
 * TransactionTemplate#executeWithoutResult} invokes the callback synchronously regardless of
 * whether the underlying transaction manager is real, so the mocked repository call inside it is
 * still observable via {@code verify(...)} below.
 */
class SpaceServiceTest {

  private SpaceRepository spaceRepository;
  private PlatformTransactionManager transactionManager;
  private SpaceService spaceService;

  @BeforeEach
  void setUp() {
    spaceRepository = mock(SpaceRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    spaceService = new SpaceService(spaceRepository, userRepository, transactionManager);
  }

  @Test
  void ensurePersonalSpaceInsertsWhenNoPersonalSpaceExistsYet() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndKind(userId, SpaceKind.PERSONAL)).thenReturn(false);

    spaceService.ensurePersonalSpace(userId, organizationId);

    verify(spaceRepository)
        .insertPersonalSpaceIfAbsent(
            any(UUID.class),
            any(UUID.class),
            any(String.class),
            any(String.class),
            eq(userId),
            eq(organizationId));
  }

  @Test
  void ensurePersonalSpaceIsANoOpWhenAPersonalSpaceAlreadyExists() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndKind(userId, SpaceKind.PERSONAL)).thenReturn(true);

    spaceService.ensurePersonalSpace(userId, organizationId);

    verify(spaceRepository, never())
        .insertPersonalSpaceIfAbsent(
            any(UUID.class),
            any(UUID.class),
            any(String.class),
            any(String.class),
            any(UUID.class),
            any(UUID.class));
  }

  @Test
  void ensurePersonalSpacePropagatesAnyFailureFromTheInsert() {
    // A genuine failure other than the partial-unique-index conflict (which the repository method
    // itself absorbs via ON CONFLICT ... DO NOTHING and therefore never throws for) - e.g. a
    // dangling ownerId violating fk_spaces_owner - must still surface to the caller, not be
    // swallowed. There is no "was it the race or a real violation" distinction to make anymore;
    // whatever the repository throws propagates as-is.
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndKind(userId, SpaceKind.PERSONAL)).thenReturn(false);
    DataIntegrityViolationException violation =
        new DataIntegrityViolationException("fk_spaces_owner violation");
    doThrow(violation)
        .when(spaceRepository)
        .insertPersonalSpaceIfAbsent(
            any(UUID.class),
            any(UUID.class),
            any(String.class),
            any(String.class),
            eq(userId),
            eq(organizationId));

    assertThatThrownBy(() -> spaceService.ensurePersonalSpace(userId, organizationId))
        .isSameAs(violation);
  }

  @Test
  void ensurePersonalSpaceDoesNotThrowWhenTheInsertSucceedsOrIsANoOp() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndKind(userId, SpaceKind.PERSONAL)).thenReturn(false);

    assertThatCode(() -> spaceService.ensurePersonalSpace(userId, organizationId))
        .doesNotThrowAnyException();
  }
}
