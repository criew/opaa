package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.TrustedProvider;
import io.opaa.auth.UserRepository;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.library.PermissionHistoryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link DirectorySyncPlanExecutor} without a trusted provider (#1331, ADR-0025 Entscheidung 4):
 * the run stops before planning - resolving the directory's subjects among nobody would read as
 * "remove every membership" - with its own outcome, a German reason and the run's audit header.
 */
class DirectorySyncPlanExecutorTest {

  private final GroupRepository groupRepository = mock(GroupRepository.class);
  private final UserRepository userRepository = mock(UserRepository.class);
  private final TrustedProvider trustedProvider = mock(TrustedProvider.class);
  private final AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
  private final DirectorySyncPlanExecutor executor =
      new DirectorySyncPlanExecutor(
          groupRepository,
          userRepository,
          trustedProvider,
          mock(GroupMembershipResolver.class),
          new DirectorySyncProperties(0.3),
          mock(PermissionHistoryService.class),
          auditEventRecorder);

  @Test
  void withoutATrustedProviderTheRunIsAbortedBeforeAnyMemberIsResolved() {
    UUID organizationId = UUID.randomUUID();
    when(groupRepository.findByOrganizationIdAndKindOrgUnit(organizationId)).thenReturn(List.of());
    when(trustedProvider.issuer()).thenReturn(Optional.empty());
    DirectorySnapshot snapshot =
        new DirectorySnapshot(
            Instant.now(),
            List.of(new DirectoryGroup("dir-1", "Referat 12", null, Set.of("member-1"))));

    SyncReport report = executor.planAndApply(organizationId, Instant.now(), snapshot);

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.ABORTED_NO_TRUSTED_PROVIDER);
    assertThat(report.message()).contains("Kein Standardanbieter");
    assertThat(report.groupsCreated()).isEmpty();
    assertThat(report.membershipChanges()).isEmpty();
    verifyNoInteractions(userRepository);
    verify(groupRepository).findByOrganizationIdAndKindOrgUnit(organizationId);
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordSystemProcessAction(audit.capture());
    assertThat(audit.getValue().outcome()).isEqualTo(AuditOutcome.FAILURE);
    assertThat(audit.getValue().after()).containsEntry("outcome", "ABORTED_NO_TRUSTED_PROVIDER");
    verify(groupRepository, org.mockito.Mockito.never()).save(any());
  }
}
