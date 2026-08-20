package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.audit.AuditEventType;
import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The race-related tests here follow the same simulation approach as {@code SpaceServiceTest}:
 * {@link UserRepository#findBySubjectAndIssuer} is stubbed to answer as it would for the loser of a
 * concurrent first login (empty before the insert attempt, present after a concurrent winner has
 * committed), and {@link UserRepository#saveAndFlush} is stubbed to throw the {@link
 * DataIntegrityViolationException} that {@code uq_users_subject_issuer} would raise for the losing
 * insert. The real, multi-threaded reproduction against Postgres - including the pool-exhaustion
 * regression a first, {@code @Transactional} version of this fix introduced and that this class
 * cannot catch - lives in {@code UserServiceCreationRaceIntegrationTest}.
 */
class UserServiceTest {

  private UserRepository userRepository;
  private SpaceService spaceService;
  private AuthProperties authProperties;
  private AuditEventRecorder auditEventRecorder;
  private UserService userService;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    spaceService = mock(SpaceService.class);
    authProperties = mock(AuthProperties.class);
    auditEventRecorder = mock(AuditEventRecorder.class);
    userService = new UserService(userRepository, spaceService, authProperties, auditEventRecorder);
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
    verify(spaceService).ensureDefaultSpace(user.getId(), Organization.DEFAULT_ID);
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
    verify(spaceService).ensureDefaultSpace(existing.getId(), Organization.DEFAULT_ID);
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

    User updated = userService.updateRole(userId, SystemRole.SYSTEM_ADMIN, UUID.randomUUID());

    assertThat(updated.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  @Test
  void updateRoleThrowsForNonexistentUser() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> userService.updateRole(userId, SystemRole.SYSTEM_ADMIN, UUID.randomUUID()))
        .isInstanceOf(UserNotFoundException.class);
  }

  /**
   * #393 code review, finding 1: reproduces the exact scenario the review names - a plain USER
   * granted AUDITOR. The pre-fix branch ({@code role == SYSTEM_ADMIN ? GRANTED : REVOKED}) wrote
   * {@code SYSTEM_ADMIN_ROLE_REVOKED} here, a false statement about a person who never held that
   * role. Reverting {@link UserService#updateRole}'s fix (restoring that two-valued branch) makes
   * this test fail with exactly that wrong event type - the reproduction proof AGENTS.md requires.
   */
  @Test
  void grantingAuditorToAPlainUserRecordsOnlyAuditorGrantedNeverSystemAdminRevoked() {
    UUID userId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    user.setSystemRole(SystemRole.USER);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());

    userService.updateRole(userId, SystemRole.AUDITOR, actorId);

    verify(auditEventRecorder, times(1))
        .recordUserActionOnSubject(
            any(),
            any(),
            eq(AuditEventType.AUDITOR_ROLE_GRANTED),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
    verify(auditEventRecorder, never())
        .recordUserActionOnSubject(
            any(),
            any(),
            eq(AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void revokingAuditorFromAPlainUserRecordsAuditorRevoked() {
    UUID userId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    user.setSystemRole(SystemRole.AUDITOR);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());

    userService.updateRole(userId, SystemRole.USER, actorId);

    verify(auditEventRecorder, times(1))
        .recordUserActionOnSubject(
            any(),
            any(),
            eq(AuditEventType.AUDITOR_ROLE_REVOKED),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  /**
   * A direct SYSTEM_ADMIN -> AUDITOR transition legitimately leaves one role and enters another -
   * both must be recorded, not just one.
   */
  @Test
  void movingDirectlyFromSystemAdminToAuditorRecordsBothARevokeAndAGrant() {
    UUID userId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());

    userService.updateRole(userId, SystemRole.AUDITOR, actorId);

    verify(auditEventRecorder, times(1))
        .recordUserActionOnSubject(
            any(),
            any(),
            eq(AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
    verify(auditEventRecorder, times(1))
        .recordUserActionOnSubject(
            any(),
            any(),
            eq(AuditEventType.AUDITOR_ROLE_GRANTED),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  void findOrCreateUserDelegatesPersonalSpaceIdempotencyToItsOwnService() {
    User existing = new User("sub1", "issuer1", "old@example.com", "Old Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    userService.findOrCreateUser("sub1", "issuer1", "old@example.com", "Old Name");

    // UserService no longer checks existence itself; ensureDefaultSpace is idempotent and is
    // always called, whether the user is new or existing.
    verify(spaceService).ensureDefaultSpace(existing.getId(), Organization.DEFAULT_ID);
  }

  @Test
  void ensuresPersonalSpaceWithoutPropagatingAFailure() {
    // The failure must not propagate to the caller (code review of #201/#305): findOrCreateUser
    // has no ambient transaction to protect (#293/#299), so a rethrown failure here would fail the
    // login request itself, and because this method runs unconditionally on every login, every
    // subsequent login for that user too - turning a provisioning failure into a lockout.
    //
    // The user is still returned successfully; the failure is only logged (see log output captured
    // by the test framework, not asserted here - the observable contract is "the call did not
    // throw").
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn(null);
    Mockito.doThrow(new RuntimeException("space provisioning failed"))
        .when(spaceService)
        .ensureDefaultSpace(any(), any());

    User user = userService.findOrCreateUser("sub1", "issuer1", "test@example.com", "Test");

    assertThat(user.getSubject()).isEqualTo("sub1");
    verify(spaceService).ensureDefaultSpace(any(), any());
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
