package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * The race-related tests here follow the same simulation approach as {@code SpaceServiceTest}:
 * {@link UserRepository#findBySubjectAndIssuer} is stubbed to answer as it would for the loser of a
 * concurrent first login (empty before the insert attempt, present after a concurrent winner has
 * committed), and {@link UserRepository#saveAndFlush} is stubbed to throw the {@link
 * DataIntegrityViolationException} that {@code uq_users_subject_issuer} would raise for the losing
 * insert. The real, multi-threaded reproduction against Postgres lives in {@code
 * UserServiceCreationRaceIntegrationTest} - a mocked {@link PlatformTransactionManager} does not
 * execute real propagation and only covers the catch block itself.
 */
class UserServiceTest {

  private UserRepository userRepository;
  private SpaceService spaceService;
  private AuthProperties authProperties;
  private UserService userService;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    spaceService = mock(SpaceService.class);
    authProperties = mock(AuthProperties.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    userService = new UserService(userRepository, spaceService, authProperties, transactionManager);
  }

  @Test
  void findOrCreateUserCreatesNewUser() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn(null);

    User user = userService.findOrCreateUser("sub1", "issuer1", "test@example.com", "Test");

    assertThat(user.getSubject()).isEqualTo("sub1");
    assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
    assertThat(user.getOrganizationId()).isEqualTo(Organization.DEFAULT_ID);
    verify(spaceService).ensurePersonalSpace(user.getId(), Organization.DEFAULT_ID);
  }

  @Test
  void findOrCreateUserUpdatesExistingUser() {
    User existing = new User("sub1", "issuer1", "old@example.com", "Old Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User user = userService.findOrCreateUser("sub1", "issuer1", "new@example.com", "New Name");

    assertThat(user.getEmail()).isEqualTo("new@example.com");
    assertThat(user.getDisplayName()).isEqualTo("New Name");
    verify(spaceService).ensurePersonalSpace(existing.getId(), Organization.DEFAULT_ID);
  }

  @Test
  void findOrCreateUserGrantsSystemAdminToInitialAdmin() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    User user = userService.findOrCreateUser("sub1", "issuer1", "admin@example.com", "Admin");

    assertThat(user.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  @Test
  void findOrCreateUserDoesNotGrantAdminForNonMatchingEmail() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    User user = userService.findOrCreateUser("sub1", "issuer1", "other@example.com", "Other");

    assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
  }

  @Test
  void updateRoleChangesUserRole() {
    UUID userId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User updated = userService.updateRole(userId, SystemRole.SYSTEM_ADMIN);

    assertThat(updated.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  @Test
  void updateRoleThrowsForNonexistentUser() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userService.updateRole(userId, SystemRole.SYSTEM_ADMIN))
        .isInstanceOf(UserNotFoundException.class);
  }

  @Test
  void findOrCreateUserDelegatesPersonalSpaceIdempotencyToSpaceService() {
    User existing = new User("sub1", "issuer1", "old@example.com", "Old Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    userService.findOrCreateUser("sub1", "issuer1", "old@example.com", "Old Name");

    // UserService no longer checks existence itself; SpaceService.ensurePersonalSpace is
    // idempotent and is always called, whether the user is new or existing.
    verify(spaceService).ensurePersonalSpace(existing.getId(), Organization.DEFAULT_ID);
  }

  @Test
  void findOrCreateUserReadsTheWinnersUserInsteadOfThrowing() {
    User winner = new User("sub1", "issuer1", "race@example.com", "Race");
    winner.setOrganizationId(Organization.DEFAULT_ID);

    // First call: this login has not seen a user yet. Second call (the race-loss fallback read):
    // a concurrent login already committed one while this insert was failing.
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(userRepository.saveAndFlush(any(User.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key value violates unique constraint"
                    + " \"users_subject_issuer_unique\""));
    when(authProperties.initialAdminEmail()).thenReturn(null);

    User user = userService.findOrCreateUser("sub1", "issuer1", "race@example.com", "Race");

    assertThat(user).isEqualTo(winner);
    verify(userRepository, times(2)).findBySubjectAndIssuer("sub1", "issuer1");
  }

  @Test
  void findOrCreateUserPropagatesViolationsUnrelatedToTheRace() {
    // The user still cannot be found after the failed insert - so the violation was not caused by
    // a concurrent winner, and must not be swallowed.
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.empty(), Optional.empty());
    DataIntegrityViolationException violation =
        new DataIntegrityViolationException("some other constraint violation");
    when(userRepository.saveAndFlush(any(User.class))).thenThrow(violation);
    when(authProperties.initialAdminEmail()).thenReturn(null);

    assertThatThrownBy(
            () -> userService.findOrCreateUser("sub1", "issuer1", "race@example.com", "Race"))
        .isSameAs(violation);
  }
}
