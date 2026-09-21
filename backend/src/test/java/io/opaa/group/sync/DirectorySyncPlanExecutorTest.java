package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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
import io.opaa.auth.local.LocalAdminAvailabilityGuard;
import io.opaa.common.ConflictException;
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
   * ADR-0036, Entscheidung 6: a lock is never refused for open ownership questions, and the one
   * exception is the last login-capable system administrator - decided by the guard of ADR-0033,
   * not by a second rule here. The withheld lock is named in the report instead of silently
   * dropped.
   */
  @Test
  void theLockOfTheLastLoginCapableAdministratorIsWithheldAndNamed() {
    User admin = account("subject-admin", SystemRole.SYSTEM_ADMIN);
    when(groupRepository.findByOrganizationIdAndProviderIdAndKindOrgUnit(
            organizationId, providerId))
        .thenReturn(List.of());
    when(groupRepository.findByOrganizationIdAndProviderIdAndKind(
            organizationId, providerId, GroupKind.IDENTITY_PROVIDER))
        .thenReturn(List.of());
    when(userRepository.findByOrganizationIdAndIssuer(organizationId, target.issuer()))
        .thenReturn(List.of(admin));
    doThrow(new ConflictException("kein weiterer", LocalAdminAvailabilityGuard.ERROR_CODE))
        .when(adminGuard)
        .requireLoginCapableAdminBesides(any(), any());

    SyncReport report =
        executor.planAndApply(
            target,
            Instant.now(),
            new DirectorySnapshot(
                Instant.now(), List.of(), List.of(new DirectoryAccount("subject-admin", false))));

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    assertThat(report.accountsLocked()).isEmpty();
    assertThat(report.accountLocksWithheld())
        .extracting(UserRef::id)
        .containsExactly(admin.getId());
    assertThat(admin.isDirectoryLocked()).isFalse();
  }

  /** An ordinary account is locked without the guard being asked at all. */
  @Test
  void anOrdinaryAccountTheDirectoryDisabledIsLocked() {
    User account = account("subject-gone", SystemRole.USER);
    when(groupRepository.findByOrganizationIdAndProviderIdAndKindOrgUnit(
            organizationId, providerId))
        .thenReturn(List.of());
    when(groupRepository.findByOrganizationIdAndProviderIdAndKind(
            organizationId, providerId, GroupKind.IDENTITY_PROVIDER))
        .thenReturn(List.of());
    when(userRepository.findByOrganizationIdAndIssuer(organizationId, target.issuer()))
        .thenReturn(List.of(account));
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
    verify(adminGuard, never()).requireLoginCapableAdminBesides(any(), any());
  }

  private User account(String subject, SystemRole role) {
    User user = new User(subject, target.issuer(), subject + "@example.com", "Konto");
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    return user;
  }
}
