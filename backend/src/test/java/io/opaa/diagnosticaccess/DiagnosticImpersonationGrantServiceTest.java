package io.opaa.diagnosticaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The befugnis rules of Leitplanke (c). The most important case is {@link
 * #aSystemAdminWithoutAGrantMayNotAssumeAnyoneElsesContext}: the whole point of the leitplanke is
 * that "ist Administrator" confers nothing here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiagnosticImpersonationGrantServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  @Mock private DiagnosticImpersonationGrantRepository grantRepository;
  @Mock private UserRepository userRepository;
  @Mock private GroupRepository groupRepository;
  @Mock private GroupMembershipResolver membershipResolver;
  @Mock private AuditEventRecorder auditEventRecorder;

  private DiagnosticImpersonationGrantService service;

  private final UUID adminId = UUID.randomUUID();
  private final UUID holderId = UUID.randomUUID();
  private final UUID targetId = UUID.randomUUID();
  private final UUID scopeGroupId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    service =
        new DiagnosticImpersonationGrantService(
            grantRepository,
            userRepository,
            groupRepository,
            membershipResolver,
            auditEventRecorder,
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(grantRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    when(userRepository.findByIdAndOrganizationId(any(), any()))
        .thenReturn(Optional.of(new User("s", "i", null, "Holder")));
    when(groupRepository.findById(scopeGroupId)).thenReturn(Optional.of(group(GroupKind.ORG_UNIT)));
  }

  @Test
  void grantsAScopedAndTimeLimitedBefugnisAndRecordsIt() {
    DiagnosticImpersonationGrant grant =
        service.grant(admin(), creation(NOW, NOW.plus(30, ChronoUnit.DAYS)));

    assertThat(grant.getScopeGroupId()).isEqualTo(scopeGroupId);
    assertThat(grant.getValidUntil()).isEqualTo(NOW.plus(30, ChronoUnit.DAYS));
    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordUserActionOnSubject(event.capture());
    assertThat(event.getValue().eventType())
        .isEqualTo(AuditEventType.DIAGNOSTIC_IMPERSONATION_GRANTED);
  }

  @Test
  void refusesAWindowLongerThanTwelveMonths() {
    assertThatThrownBy(() -> service.grant(admin(), creation(NOW, NOW.plus(400, ChronoUnit.DAYS))))
        .isInstanceOf(ValidationException.class);
    verify(grantRepository, never()).save(any());
  }

  @Test
  void refusesAnAdHocGroupAsGeltungsbereich() {
    when(groupRepository.findById(scopeGroupId)).thenReturn(Optional.of(group(GroupKind.AD_HOC)));

    assertThatThrownBy(() -> service.grant(admin(), creation(NOW, NOW.plus(1, ChronoUnit.DAYS))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void onlyAnAdministratorMayGrantTheBefugnis() {
    assertThatThrownBy(
            () ->
                service.grant(ordinaryUser(holderId), creation(NOW, NOW.plus(1, ChronoUnit.DAYS))))
        .isInstanceOf(AccessDeniedException.class);
  }

  /** The core of Leitplanke (c): the befugnis is not derived from any role, including this one. */
  @Test
  void aSystemAdminWithoutAGrantMayNotAssumeAnyoneElsesContext() {
    when(grantRepository.findActive(ORGANIZATION_ID, adminId, NOW)).thenReturn(List.of());

    assertThatThrownBy(() -> service.requireImpersonationPermission(admin(), targetId))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aGrantForADifferentOrganisationseinheitIsNoPermissionForThisPerson() {
    when(grantRepository.findActive(ORGANIZATION_ID, holderId, NOW))
        .thenReturn(List.of(activeGrant()));
    when(membershipResolver.groupIdsForUser(targetId)).thenReturn(Set.of(UUID.randomUUID()));

    assertThatThrownBy(
            () -> service.requireImpersonationPermission(ordinaryUser(holderId), targetId))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aGrantForTheTargetsOrganisationseinheitPermitsTheDiagnosis() {
    DiagnosticImpersonationGrant grant = activeGrant();
    when(grantRepository.findActive(ORGANIZATION_ID, holderId, NOW)).thenReturn(List.of(grant));
    when(membershipResolver.groupIdsForUser(targetId)).thenReturn(Set.of(scopeGroupId));

    assertThat(service.requireImpersonationPermission(ordinaryUser(holderId), targetId))
        .isSameAs(grant);
  }

  @Test
  void revokingRecordsItsOwnEventAndIsIdempotent() {
    DiagnosticImpersonationGrant grant = activeGrant();
    when(grantRepository.findByIdAndOrganizationId(grant.getId(), ORGANIZATION_ID))
        .thenReturn(Optional.of(grant));

    service.revoke(admin(), grant.getId());
    Instant firstRevocation = grant.getRevokedAt();
    service.revoke(admin(), grant.getId());

    assertThat(grant.getRevokedAt()).isEqualTo(firstRevocation);
    assertThat(grant.isActiveAt(NOW)).isFalse();
  }

  private DiagnosticImpersonationGrant activeGrant() {
    return new DiagnosticImpersonationGrant(
        ORGANIZATION_ID,
        holderId,
        scopeGroupId,
        NOW.minus(1, ChronoUnit.DAYS),
        NOW.plus(30, ChronoUnit.DAYS),
        adminId,
        NOW.minus(1, ChronoUnit.DAYS));
  }

  private DiagnosticImpersonationGrantCreation creation(Instant from, Instant until) {
    return new DiagnosticImpersonationGrantCreation(holderId, scopeGroupId, from, until);
  }

  private Group group(GroupKind kind) {
    return new Group(ORGANIZATION_ID, kind, "Amt für Personal", null, null, null);
  }

  private CurrentUser admin() {
    return CurrentUser.of(adminId, ORGANIZATION_ID, SystemRole.SYSTEM_ADMIN, "Admin");
  }

  private CurrentUser ordinaryUser(UUID id) {
    return CurrentUser.of(id, ORGANIZATION_ID, SystemRole.USER, "Nutzer");
  }
}
