package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionHistoryService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
  private final DirectorySyncPlanExecutor executor =
      new DirectorySyncPlanExecutor(
          groupRepository,
          userRepository,
          mock(GroupMembershipResolver.class),
          new DirectorySyncProperties(0.3, true),
          mock(PermissionHistoryService.class),
          mock(AuditEventRecorder.class),
          pendingPlanRepository);

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
}
