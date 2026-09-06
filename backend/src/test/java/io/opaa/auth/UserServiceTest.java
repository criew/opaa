package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.observability.AuthMetrics;
import io.opaa.organization.Organization;
import io.opaa.space.SpaceService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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
  private AuthMetrics authMetrics;
  private MutableClock clock;
  private UserService userService;

  /**
   * #833: a settable {@link Clock}, not {@link Clock#fixed}, so a test can advance time between two
   * {@code findOrCreateUser} calls within the same test method to exercise the lastLoginAt-write
   * threshold - {@code Clock.fixed} would need a whole new {@code UserService} instance per
   * timestamp instead.
   */
  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    spaceService = mock(SpaceService.class);
    authProperties = mock(AuthProperties.class);
    auditEventRecorder = mock(AuditEventRecorder.class);
    // A real AuthMetrics backed by a real (test-local) registry, not a mock (#307 review, finding
    // 3): this lets ensuresPersonalSpaceWithoutPropagatingAFailure below assert the Micrometer
    // counter itself actually incremented, not just that some method was called on a mock.
    authMetrics = new AuthMetrics(new SimpleMeterRegistry());
    clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    // #1330: the initial-admin rule is issuer-bound (ADR-0025); in this mocked "dev" mode the dev
    // issuer "issuer1" is the trusted one, so the address tests below keep their meaning.
    when(authProperties.mode()).thenReturn("dev");
    when(authProperties.dev()).thenReturn(new AuthProperties.DevAuth("issuer1", null, null));
    userService =
        new UserService(
            userRepository,
            spaceService,
            new InitialAdminPolicy(authProperties, mock(OidcProviderRepository.class)),
            auditEventRecorder,
            authMetrics,
            clock);
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
    // #307: a genuinely new user (this call's own insert won) skips the redundant existsBy round
    // trip via the ensureDefaultSpaceForNewUser fast path - see UserService#ensurePersonalSpace.
    verify(spaceService).ensureDefaultSpaceForNewUser(user.getId(), Organization.DEFAULT_ID);
  }

  @Test
  void findOrCreateUserUpdatesExistingUser() {
    User existing = new User("sub1", "issuer1", "old@example.com", "Old Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    existing.setLastLoginAt(clock.instant().minus(Duration.ofMinutes(10)));
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User user = userService.findOrCreateUser("sub1", "issuer1", "new@example.com", "New Name");

    assertThat(user.getEmail()).isEqualTo("new@example.com");
    assertThat(user.getDisplayName()).isEqualTo("New Name");
    verify(spaceService).ensureDefaultSpace(existing.getId(), Organization.DEFAULT_ID);
  }

  /**
   * #833 acceptance criterion: two consecutive requests for the same, already-known user within the
   * threshold produce exactly one UPDATE, not zero and not two. Before the fix, {@code
   * updateExistingUser} called {@code save()} unconditionally on every call, so this would observe
   * {@code times(2)} instead.
   */
  @Test
  void twoRequestsWithinTheThresholdWriteLastLoginAtExactlyOnce() {
    User existing = new User("sub1", "issuer1", "same@example.com", "Same Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    existing.setLastLoginAt(clock.instant().minus(Duration.ofMinutes(10)));
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    userService.findOrCreateUser("sub1", "issuer1", "same@example.com", "Same Name");
    clock.instant = clock.instant.plus(Duration.ofSeconds(30));
    userService.findOrCreateUser("sub1", "issuer1", "same@example.com", "Same Name");

    verify(userRepository, times(1)).save(any(User.class));
  }

  /** #833: once the threshold has elapsed, the next request must refresh lastLoginAt again. */
  @Test
  void requestAfterTheThresholdWritesLastLoginAtAgain() {
    User existing = new User("sub1", "issuer1", "same@example.com", "Same Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    existing.setLastLoginAt(clock.instant());
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    clock.instant = clock.instant.plus(Duration.ofMinutes(5));
    User user = userService.findOrCreateUser("sub1", "issuer1", "same@example.com", "Same Name");

    assertThat(user.getLastLoginAt()).isEqualTo(clock.instant);
    verify(userRepository, times(1)).save(any(User.class));
  }

  /**
   * #833: a changed claim must be written immediately, even though {@code lastLoginAt} itself is
   * still within the throttling threshold and would not by itself trigger a write.
   */
  @Test
  void changedEmailWithinTheThresholdIsStillWrittenImmediately() {
    User existing = new User("sub1", "issuer1", "old@example.com", "Same Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    existing.setLastLoginAt(clock.instant());
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User user = userService.findOrCreateUser("sub1", "issuer1", "new@example.com", "Same Name");

    assertThat(user.getEmail()).isEqualTo("new@example.com");
    verify(userRepository, times(1)).save(any(User.class));
  }

  /** #833: same as the email case above, but for the independent displayName condition. */
  @Test
  void changedDisplayNameWithinTheThresholdIsStillWrittenImmediately() {
    User existing = new User("sub1", "issuer1", "same@example.com", "Old Name");
    existing.setOrganizationId(Organization.DEFAULT_ID);
    existing.setLastLoginAt(clock.instant());
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1"))
        .thenReturn(Optional.of(existing));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User user = userService.findOrCreateUser("sub1", "issuer1", "same@example.com", "New Name");

    assertThat(user.getDisplayName()).isEqualTo("New Name");
    verify(userRepository, times(1)).save(any(User.class));
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
  void findOrCreateUserDoesNotGrantAdminThroughAnotherIssuer() {
    // ADR-0025, Entscheidung 3 (#1330): the initial administrator's address issued by a provider
    // other than the trusted one is a plain user - the rule cannot be captured.
    when(userRepository.findBySubjectAndIssuer("sub1", "https://partner.example/realms/b"))
        .thenReturn(Optional.empty());
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    User user =
        userService.findOrCreateUser(
            "sub1", "https://partner.example/realms/b", "admin@example.com", "Admin");

    assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
  }

  @Test
  void findOrCreateUserDoesNotGrantAdminForNonMatchingEmail() {
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn("admin@example.com");

    User user = userService.findOrCreateUser("sub1", "issuer1", "other@example.com", "Other");

    assertThat(user.getSystemRole()).isEqualTo(SystemRole.USER);
  }

  // #778 review, finding 4: reproduces the "unbounded full list on every picker mount" behaviour
  // this method replaces - reverting to `return findAllInOrganization(organizationId)` here
  // (ignoring the query) would make searchInOrganizationRejectsAQueryBelowTheMinimumLength and
  // searchInOrganizationCapsTheResultAtTheConfiguredLimit fail: the first because
  // userRepository.searchByOrganizationId would never even be consulted (verifyNoInteractions),
  // the second because the unbounded findByOrganizationId path carries no Pageable to cap with.
  @Test
  void searchInOrganizationRejectsAQueryBelowTheMinimumLength() {
    UUID organizationId = UUID.randomUUID();

    assertThat(userService.searchInOrganization(organizationId, "a")).isEmpty();
    assertThat(userService.searchInOrganization(organizationId, "  ")).isEmpty();
    assertThat(userService.searchInOrganization(organizationId, null)).isEmpty();
    verifyNoInteractions(userRepository);
  }

  @Test
  void searchInOrganizationTrimsAndForwardsAQueryAtTheMinimumLength() {
    UUID organizationId = UUID.randomUUID();
    User match = new User("sub1", "issuer1", "colleague@example.com", "Colleague");
    when(userRepository.searchByOrganizationId(eq(organizationId), eq("co"), any(Pageable.class)))
        .thenReturn(List.of(match));

    assertThat(userService.searchInOrganization(organizationId, "  co  ")).containsExactly(match);
  }

  @Test
  void searchInOrganizationCapsTheResultAtTheConfiguredLimit() {
    UUID organizationId = UUID.randomUUID();
    when(userRepository.searchByOrganizationId(
            eq(organizationId), anyString(), any(Pageable.class)))
        .thenAnswer(inv -> List.of());

    userService.searchInOrganization(organizationId, "colleague");

    verify(userRepository)
        .searchByOrganizationId(eq(organizationId), eq("colleague"), eq(PageRequest.of(0, 20)));
  }

  private CurrentUser actorInOrganization(UUID organizationId) {
    return CurrentUser.of(UUID.randomUUID(), organizationId, SystemRole.SYSTEM_ADMIN, "Admin");
  }

  @Test
  void updateRoleChangesUserRole() {
    UUID organizationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    user.setOrganizationId(organizationId);
    when(userRepository.findByIdAndOrganizationId(userId, organizationId))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());

    User updated =
        userService.updateRole(
            userId, SystemRole.SYSTEM_ADMIN, actorInOrganization(organizationId));

    assertThat(updated.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
  }

  @Test
  void updateRoleThrowsForNonexistentUser() {
    UUID organizationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(userRepository.findByIdAndOrganizationId(userId, organizationId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                userService.updateRole(
                    userId, SystemRole.SYSTEM_ADMIN, actorInOrganization(organizationId)))
        .isInstanceOf(UserNotFoundException.class);
  }

  /**
   * #271: reproduces the exact organization-boundary gap the issue names - a target user existing,
   * but in a different organization than the acting SYSTEM_ADMIN. Before the fix, {@code
   * updateRole} looked the target up by id alone ({@code userRepository.findById}), so it would
   * find and change this user's role regardless of organization; the {@code
   * findByIdAndOrganizationId} stub below (scoped to the actor's organization, not the target
   * user's) never matches, exactly reproducing that gap. Consistent with {@code
   * SpaceService#requireUserInOrganization}, the rejection is a 404-mapped {@link
   * UserNotFoundException}, not a 403, so a caller cannot distinguish "no such user" from "user in
   * another organization".
   */
  @Test
  void updateRoleRejectsATargetUserFromAnotherOrganization() {
    UUID actorOrganizationId = UUID.randomUUID();
    UUID targetOrganizationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    User targetUser = new User("sub1", "issuer1", "test@example.com", "Test");
    targetUser.setOrganizationId(targetOrganizationId);
    // The target user exists under its own organization - stubbing the pre-fix lookup
    // (findById(userId) alone) to find it too is what makes this test actually distinguish the
    // fix from the bug: with the pre-#271 code, which looked the target up by id alone, this stub
    // is exactly what let it find and change a foreign-organization user's role regardless of the
    // actor's own organization. The fixed code never calls findById(userId) alone for this lookup,
    // only the org-scoped findByIdAndOrganizationId (deliberately left unstubbed for the actor's
    // organization, so it returns empty).
    when(userRepository.findById(userId)).thenReturn(Optional.of(targetUser));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    assertThatThrownBy(
            () ->
                userService.updateRole(
                    userId, SystemRole.SYSTEM_ADMIN, actorInOrganization(actorOrganizationId)))
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
    UUID organizationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.USER);
    when(userRepository.findByIdAndOrganizationId(userId, organizationId))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());

    userService.updateRole(userId, SystemRole.AUDITOR, actorInOrganization(organizationId));

    verify(auditEventRecorder, times(1))
        .recordUserActionOnSubject(
            argThat(event -> event.eventType() == AuditEventType.AUDITOR_ROLE_GRANTED));
    verify(auditEventRecorder, never())
        .recordUserActionOnSubject(
            argThat(event -> event.eventType() == AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED));
  }

  @Test
  void revokingAuditorFromAPlainUserRecordsAuditorRevoked() {
    UUID organizationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.AUDITOR);
    when(userRepository.findByIdAndOrganizationId(userId, organizationId))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());

    userService.updateRole(userId, SystemRole.USER, actorInOrganization(organizationId));

    verify(auditEventRecorder, times(1))
        .recordUserActionOnSubject(
            argThat(event -> event.eventType() == AuditEventType.AUDITOR_ROLE_REVOKED));
  }

  /**
   * A direct SYSTEM_ADMIN -> AUDITOR transition legitimately leaves one role and enters another -
   * both must be recorded, not just one.
   */
  @Test
  void movingDirectlyFromSystemAdminToAuditorRecordsBothARevokeAndAGrant() {
    UUID organizationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    User user = new User("sub1", "issuer1", "test@example.com", "Test");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(userRepository.findByIdAndOrganizationId(userId, organizationId))
        .thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());

    userService.updateRole(userId, SystemRole.AUDITOR, actorInOrganization(organizationId));

    verify(auditEventRecorder, times(1))
        .recordUserActionOnSubject(
            argThat(event -> event.eventType() == AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED));
    verify(auditEventRecorder, times(1))
        .recordUserActionOnSubject(
            argThat(event -> event.eventType() == AuditEventType.AUDITOR_ROLE_GRANTED));
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
    // throw") and recorded in AuthMetrics (asserted below, #307 review, finding 3).
    when(userRepository.findBySubjectAndIssuer("sub1", "issuer1")).thenReturn(Optional.empty());
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    when(authProperties.initialAdminEmail()).thenReturn(null);
    Mockito.doThrow(new RuntimeException("space provisioning failed"))
        .when(spaceService)
        .ensureDefaultSpaceForNewUser(any(), any());

    User user = userService.findOrCreateUser("sub1", "issuer1", "test@example.com", "Test");

    assertThat(user.getSubject()).isEqualTo("sub1");
    verify(spaceService).ensureDefaultSpaceForNewUser(any(), any());
    assertThat(authMetrics.personalSpaceProvisioningFailedCount()).isEqualTo(1.0);
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
    // #307: a race loser did not itself create the row - the winner might already have provisioned
    // the personal space - so it must keep using the idempotent ensureDefaultSpace, never the
    // existsBy-skipping ensureDefaultSpaceForNewUser fast path reserved for a genuine winner.
    verify(spaceService).ensureDefaultSpace(winner.getId(), Organization.DEFAULT_ID);
    verify(spaceService, Mockito.never()).ensureDefaultSpaceForNewUser(any(), any());
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
