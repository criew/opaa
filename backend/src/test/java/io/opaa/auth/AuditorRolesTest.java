package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ConflictException;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The open AUDITOR request and the notice to every auditor (#3356): who learns of a request and of
 * a grant, and whose credentials the administration may not touch.
 */
class AuditorRolesTest {

  private final UUID organizationId = UUID.randomUUID();
  private final AuditorRoleRequestRepository requests = mock(AuditorRoleRequestRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final NotificationService notifications = mock(NotificationService.class);
  private AuditorRoles auditorRoles;
  private User existingAuditor;
  private User otherAuditor;
  private User candidate;

  @BeforeEach
  void setUp() {
    auditorRoles =
        new AuditorRoles(
            requests,
            users,
            credentials,
            new AuditorRoleProperties("erste.revision@stadt.example"),
            audit,
            notifications,
            Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC));
    existingAuditor = account("revision-a@stadt.example", SystemRole.AUDITOR);
    otherAuditor = account("revision-b@stadt.example", SystemRole.AUDITOR);
    candidate = account("kandidat@stadt.example", SystemRole.USER);
    when(users.findByOrganizationIdAndSystemRole(organizationId, SystemRole.AUDITOR))
        .thenReturn(List.of(existingAuditor, otherAuditor));
    when(audit.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    when(requests.findByUserId(any())).thenReturn(Optional.empty());
  }

  private User account(String email, SystemRole role) {
    User user = new User("sub-" + email, "issuer1", email, "Person " + email);
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    return user;
  }

  @Test
  void aRequestIsAuditedUnderTheAdministratorAndToldToEveryAuditor() {
    CurrentUser admin =
        CurrentUser.of(UUID.randomUUID(), organizationId, SystemRole.SYSTEM_ADMIN, "Admin");

    auditorRoles.request(candidate, admin);

    verify(requests).saveAndFlush(argThat(r -> r.getUserId().equals(candidate.getId())));
    verify(audit)
        .recordUserActionOnSubject(
            argThat(
                event ->
                    event.eventType() == AuditEventType.AUDITOR_ROLE_REQUESTED
                        && admin.id().equals(event.actorUserId())));
    for (User auditor : List.of(existingAuditor, otherAuditor)) {
      verify(notifications)
          .notify(
              eq(organizationId),
              eq(auditor.getId()),
              eq(NotificationType.AUDITOR_ROLE_REQUESTED),
              eq(AuditObjectType.USER_ACCOUNT),
              eq(candidate.getId()),
              any(),
              any());
    }
  }

  /** Every grant reaches every other auditor - whichever way it came, the claim included. */
  @Test
  void aGrantIsToldToEveryOtherAuditorButNotToTheNewOne() {
    auditorRoles.onGranted(new AuditorRoleGrantedEvent(existingAuditor));

    verify(notifications)
        .notify(
            eq(organizationId),
            eq(otherAuditor.getId()),
            eq(NotificationType.AUDITOR_ROLE_GRANTED),
            eq(AuditObjectType.USER_ACCOUNT),
            eq(existingAuditor.getId()),
            any(),
            argThat(body -> body.contains(existingAuditor.getDisplayName())));
    verify(notifications, never())
        .notify(any(), eq(existingAuditor.getId()), any(), any(), any(), any(), any());
  }

  @Test
  void theCredentialsOfAnAuditorOrARequestedAccountStayWithItsPerson() {
    assertThatThrownBy(() -> auditorRoles.requireCredentialsWithPerson(existingAuditor, "X"))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).getCode())
        .isEqualTo(AuditorRoles.AUDITOR_ACCOUNT);
    when(requests.findByUserId(candidate.getId()))
        .thenReturn(
            Optional.of(
                new AuditorRoleRequest(
                    organizationId, candidate.getId(), UUID.randomUUID(), Instant.now())));
    assertThatThrownBy(() -> auditorRoles.requireCredentialsWithPerson(candidate, "X"))
        .isInstanceOf(ConflictException.class);

    User plain = account("plain@stadt.example", SystemRole.USER);
    auditorRoles.requireCredentialsWithPerson(plain, "X");
  }

  @Test
  void onlyTheConfiguredAddressNamesTheFirstAuditor() {
    assertThat(
            auditorRoles.namesFirstAuditor(
                account("Erste.Revision@stadt.example", SystemRole.USER)))
        .isTrue();
    assertThat(auditorRoles.namesFirstAuditor(candidate)).isFalse();
    assertThat(new AuditorRoleProperties(null).namesFirstAuditor("")).isFalse();
  }
}
