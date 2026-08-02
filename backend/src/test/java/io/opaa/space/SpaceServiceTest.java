package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
 * Simulates the two concurrent first-login race #265 describes, without relying on real threads or
 * timing: {@link SpaceRepository#existsByOwnerIdAndKind} is stubbed to answer as it would for the
 * loser of the race (false before the attempt, true after a concurrent winner has committed), and
 * {@link SpaceRepository#saveAndFlush} is stubbed to throw the {@link
 * DataIntegrityViolationException} that {@code uk_spaces_personal_owner} (migration 010) would
 * raise for the losing insert.
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
  void ensurePersonalSpaceReadsTheWinnersSpaceInsteadOfThrowing() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    // First call: this login has not created a personal space yet. Second call (the race-loss
    // fallback check): a concurrent login already committed one while this insert was failing.
    when(spaceRepository.existsByOwnerIdAndKind(userId, SpaceKind.PERSONAL))
        .thenReturn(false, true);
    when(spaceRepository.saveAndFlush(any(Space.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uk_spaces_personal_owner\""));

    assertThatCode(() -> spaceService.ensurePersonalSpace(userId, organizationId))
        .doesNotThrowAnyException();

    verify(spaceRepository, times(2)).existsByOwnerIdAndKind(userId, SpaceKind.PERSONAL);
  }

  @Test
  void ensurePersonalSpacePropagatesViolationsUnrelatedToTheRace() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    // The personal space still does not exist after the failed insert - so the violation was not
    // caused by a concurrent winner, and must not be swallowed.
    when(spaceRepository.existsByOwnerIdAndKind(userId, SpaceKind.PERSONAL))
        .thenReturn(false, false);
    DataIntegrityViolationException violation =
        new DataIntegrityViolationException("some other constraint violation");
    when(spaceRepository.saveAndFlush(any(Space.class))).thenThrow(violation);

    assertThatThrownBy(() -> spaceService.ensurePersonalSpace(userId, organizationId))
        .isSameAs(violation);
  }

  @Test
  void ensurePersonalSpaceIsANoOpWhenAPersonalSpaceAlreadyExists() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndKind(userId, SpaceKind.PERSONAL)).thenReturn(true);

    spaceService.ensurePersonalSpace(userId, organizationId);

    verify(spaceRepository, times(0)).saveAndFlush(any(Space.class));
  }
}
