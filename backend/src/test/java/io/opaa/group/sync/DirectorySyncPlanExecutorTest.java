package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.UserRepository.DirectoryAccountState;
import io.opaa.auth.local.LocalAdminAvailabilityGuard;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.permission.AccountStateHistoryService;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionHistoryService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * {@link DirectorySyncPlanExecutor}'s binding to one identity provider (#1816, ADR-0036
 * Entscheidung 2 and 3): a run reads only that provider's groups, and the provider's token groups
 * are reported as no longer maintained without being touched.
 */
class DirectorySyncPlanExecutorTest {

  private final GroupRepository groupRepository = mock(GroupRepository.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final DirectorySyncPendingPlanRepository pendingPlanRepository =
      mock(DirectorySyncPendingPlanRepository.class);
  private final LocalAdminAvailabilityGuard adminGuard = mock(LocalAdminAvailabilityGuard.class);
  private final AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
  private final DirectorySyncPlanExecutor executor =
      new DirectorySyncPlanExecutor(
          groupRepository,
          userRepository,
          mock(GroupMembershipResolver.class),
          new DirectorySyncProperties(0.3, true),
          mock(PermissionHistoryService.class),
          auditEventRecorder,
          pendingPlanRepository,
          mock(AccountStateHistoryService.class),
          adminGuard,
          mock(ApplicationEventPublisher.class));

  private final UUID organizationId = UUID.randomUUID();
  private final UUID providerId = UUID.randomUUID();
  private final SyncTarget target =
      new SyncTarget(organizationId, providerId, "https://idp.example/realms/a", "Haus A");

  /**
   * Regression guard for #1816: a run never reads the organization's ORG_UNIT groups as a whole.
   * Doing so would let one provider's run dissolve another provider's groups - they are simply not
   * reported by the directory it read.
   */
  @Test
  void aRunOnlyEverReadsItsOwnProvidersGroups() {
    when(groupRepository.findByOrganizationIdAndProviderIdAndKindOrgUnit(
            organizationId, providerId))
        .thenReturn(List.of());
    when(groupRepository.findByOrganizationIdAndProviderIdAndKind(
            organizationId, providerId, GroupKind.IDENTITY_PROVIDER))
        .thenReturn(List.of());
    when(userRepository.findByOrganizationIdAndIssuerAndSubjectIn(any(), any(), any()))
        .thenReturn(List.of());

    SyncReport report =
        executor.planOnly(
            target,
            Instant.now(),
            new DirectorySnapshot(
                Instant.now(),
                List.of(
                    new DirectoryGroup("dir-1", "Referat 12", null, null, Set.of("member-1")))));

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.DRY_RUN);
    verify(groupRepository)
        .findByOrganizationIdAndProviderIdAndKindOrgUnit(organizationId, providerId);
    verify(groupRepository, never()).findByOrganizationIdAndKindOrgUnit(any());
    verify(groupRepository, never()).save(any());
  }

  /**
   * ADR-0036, Entscheidung 3 and Personalrat C4: the change of mechanism names the token groups as
   * no longer maintained and leaves them exactly as they are - nothing is revoked silently.
   */
  @Test
  void theProvidersTokenGroupsAreReportedAsUnmaintainedAndLeftUntouched() {
    Group tokenGroup =
        new Group(
            organizationId,
            GroupKind.IDENTITY_PROVIDER,
            "Referat 12",
            null,
            providerId,
            "Referat 12",
            null,
            null);
    when(groupRepository.findByOrganizationIdAndProviderIdAndKindOrgUnit(
            organizationId, providerId))
        .thenReturn(List.of());
    when(groupRepository.findByOrganizationIdAndProviderIdAndKind(
            organizationId, providerId, GroupKind.IDENTITY_PROVIDER))
        .thenReturn(List.of(tokenGroup));
    when(userRepository.findByOrganizationIdAndIssuerAndSubjectIn(any(), any(), any()))
        .thenReturn(List.of());

    SyncReport report =
        executor.planAndApply(
            target,
            Instant.now(),
            new DirectorySnapshot(
                Instant.now(),
                List.of(new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of()))));

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.unmaintainedTokenGroups())
        .extracting(GroupChange::name)
        .containsExactly("Referat 12");
    assertThat(report.groupsDissolved()).isEmpty();
    assertThat(tokenGroup.isDissolved()).isFalse();
  }

  /**
   * An ordinary account is locked without the guard being asked at all - only a {@code
   * SYSTEM_ADMIN} reaches it. That the guard withholds the lock of the <b>last</b> login-capable
   * administrator is exercised in {@code DirectoryAccountLockIntegrationTest} against the real
   * guard: it takes part in the run's transaction, so a mock cannot show what its refusal does to
   * the run.
   */
  @Test
  void anOrdinaryAccountTheDirectoryDisabledIsLocked() {
    User account = account("subject-gone", SystemRole.USER);
    when(groupRepository.findByOrganizationIdAndProviderIdAndKindOrgUnit(
            organizationId, providerId))
        .thenReturn(List.of());
    when(groupRepository.findByOrganizationIdAndProviderIdAndKind(
            organizationId, providerId, GroupKind.IDENTITY_PROVIDER))
        .thenReturn(List.of());
    when(userRepository.findAccountStatesOf(organizationId, target.issuer()))
        .thenReturn(List.of(stateOf(account)));
    when(userRepository.findAllById(List.of(account.getId()))).thenReturn(List.of(account));
    // The audit entry about an account names its pseudonym, never its id (#392).
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());

    SyncReport report =
        executor.planAndApply(
            target,
            Instant.now(),
            new DirectorySnapshot(
                Instant.now(), List.of(), List.of(new DirectoryAccount("subject-gone", false))));

    assertThat(report.accountsLocked()).extracting(UserRef::id).containsExactly(account.getId());
    assertThat(account.isDirectoryLocked()).isTrue();
    verify(adminGuard, never()).hasLoginCapableAdminBesides(any(), any());
  }

  private User account(String subject, SystemRole role) {
    User user = new User(subject, target.issuer(), subject + "@example.com", "Konto");
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    return user;
  }

  /** The projection the run plans from, filled from an entity the test already has. */
  private static DirectoryAccountState stateOf(User user) {
    return new DirectoryAccountState() {
      @Override
      public UUID getId() {
        return user.getId();
      }

      @Override
      public String getSubject() {
        return user.getSubject();
      }

      @Override
      public SystemRole getSystemRole() {
        return user.getSystemRole();
      }

      @Override
      public Instant getDirectoryLockedAt() {
        return user.getDirectoryLockedAt();
      }

      @Override
      public String getDisplayName() {
        return user.getDisplayName();
      }

      @Override
      public String getEmail() {
        return user.getEmail();
      }
    };
  }
}
