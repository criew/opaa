package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.NotificationType;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ConflictException;
import io.opaa.mail.MailConfigurationChangedEvent;
import io.opaa.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The AUDITOR request and the notices to every auditor: who learns of a request, a grant, a
 * withdrawal and a change of the mail channels, and whose credentials the administration may not
 * touch.
 */
class AuditorRolesTest {

  private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

  private final UUID organizationId = UUID.randomUUID();
  private final AuditorRoleRequestRepository requests = mock(AuditorRoleRequestRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final LocalCredentialsRepository credentials = mock(LocalCredentialsRepository.class);
  private final OidcProviderRepository providers = mock(OidcProviderRepository.class);
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
            providers,
            new AuditorRoleProperties("erste.revision@stadt.example"),
            audit,
            notifications,
            Clock.fixed(NOW, ZoneOffset.UTC));
    existingAuditor = account("revision-a@stadt.example", SystemRole.AUDITOR);
    otherAuditor = account("revision-b@stadt.example", SystemRole.AUDITOR);
    candidate = account("kandidat@stadt.example", SystemRole.USER);
    when(users.findByOrganizationIdAndSystemRole(organizationId, SystemRole.AUDITOR))
        .thenReturn(List.of(existingAuditor, otherAuditor));
    when(audit.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    when(requests.findOpenByUserId(any())).thenReturn(Optional.empty());
    when(requests.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(1);
  }

  private User account(String email, SystemRole role) {
    User user = new User("sub-" + email, "issuer1", email, "Person " + email);
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    return user;
  }

  private OidcProvider provider() {
    OidcProvider provider = mock(OidcProvider.class);
    when(provider.getId()).thenReturn(UUID.randomUUID());
    when(provider.getDisplayName()).thenReturn("Stadt-IdP");
    return provider;
  }

  @Test
  void aRequestIsAuditedUnderTheAdministratorAndToldToEveryAuditorWithTheAddress() {
    CurrentUser admin =
        CurrentUser.of(UUID.randomUUID(), organizationId, SystemRole.SYSTEM_ADMIN, "Admin");
    LocalCredentials local = new LocalCredentials(candidate.getId(), "Grund", NOW);
    local.markEmailChangedByAdmin(Instant.parse("2026-10-28T09:00:00Z"));
    when(credentials.findById(candidate.getId())).thenReturn(Optional.of(local));

    auditorRoles.request(candidate, admin);

    verify(requests)
        .insertIfAbsent(
            any(), eq(organizationId), eq(candidate.getId()), eq(admin.id()), isNull(), eq(NOW));
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
              argThat(
                  body ->
                      body.contains("kandidat@stadt.example") && body.contains("am 28.10.2026")));
    }
  }

  /** Two concurrent requests: the one whose insert lost neither audits nor notifies. */
  @Test
  void aRequestThatAlreadyExistsIsNeitherAuditedNorToldAgain() {
    when(requests.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0);

    auditorRoles.request(
        candidate,
        CurrentUser.of(UUID.randomUUID(), organizationId, SystemRole.SYSTEM_ADMIN, "Admin"));

    verifyNoInteractions(notifications);
    verify(audit, never()).recordUserActionOnSubject(any());
  }

  @Test
  void aClaimAsksUnderTheProviderWhileAnAuditorExists() {
    OidcProvider provider = provider();
    UUID providerId = provider.getId();

    assertThat(auditorRoles.requestFromProvider(candidate, provider)).isTrue();

    verify(requests)
        .insertIfAbsent(
            any(), eq(organizationId), eq(candidate.getId()), isNull(), eq(providerId), eq(NOW));
    verify(audit)
        .recordSystemProcessAction(
            argThat(event -> event.eventType() == AuditEventType.AUDITOR_ROLE_REQUESTED));
  }

  @Test
  void withoutAnAuditorAClaimAsksForNothing() {
    when(users.findByOrganizationIdAndSystemRole(organizationId, SystemRole.AUDITOR))
        .thenReturn(List.of());

    assertThat(auditorRoles.requestFromProvider(candidate, provider())).isFalse();

    verify(requests, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
  }

  /** A declined claim request stays as a marker; the next sign-in neither asks nor notifies. */
  @Test
  void anExistingClaimRequestIsNotAskedAgain() {
    when(requests.existsByUserId(candidate.getId())).thenReturn(true);

    assertThat(auditorRoles.requestFromProvider(candidate, provider())).isTrue();

    verify(requests, never()).insertIfAbsent(any(), any(), any(), any(), any(), any());
    verifyNoInteractions(notifications);
  }

  @Test
  void anAuditorDecliningAClaimRequestKeepsItAsDeclined() {
    AuditorRoleRequest request =
        new AuditorRoleRequest(organizationId, candidate.getId(), null, UUID.randomUUID(), NOW);
    when(requests.markDeclined(request.getId(), NOW)).thenReturn(1);

    auditorRoles.declineByAuditor(request, candidate, existingAuditor.getId());

    verify(requests).markDeclined(request.getId(), NOW);
    verify(requests, never()).deleteAllOfUser(any());
    verify(audit)
        .recordUserActionOnSubject(
            argThat(event -> event.eventType() == AuditEventType.AUDITOR_ROLE_REQUEST_DECLINED));
  }

  /** Two concurrent declines: only the one that removed the row audits. */
  @Test
  void aDeclineThatFoundNothingLeftIsNotAudited() {
    AuditorRoleRequest request =
        new AuditorRoleRequest(organizationId, candidate.getId(), UUID.randomUUID(), null, NOW);
    when(requests.deleteAllOfUser(candidate.getId())).thenReturn(0);

    auditorRoles.declineByAuditor(request, candidate, existingAuditor.getId());

    verify(audit, never()).recordUserActionOnSubject(any());
  }

  /** Every grant reaches every other auditor. */
  @Test
  void aGrantIsToldToEveryOtherAuditorButNotToTheNewOne() {
    auditorRoles.onGranted(new AuditorRoleGrantedEvent(existingAuditor));

    verify(requests).deleteAllOfUser(existingAuditor.getId());
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
  void aWithdrawalIsToldToEveryRemainingAuditor() {
    User leaving = account("weg@stadt.example", SystemRole.USER);

    auditorRoles.onRevoked(new AuditorRoleRevokedEvent(leaving));

    for (User auditor : List.of(existingAuditor, otherAuditor)) {
      verify(notifications)
          .notify(
              eq(organizationId),
              eq(auditor.getId()),
              eq(NotificationType.AUDITOR_ROLE_REVOKED),
              eq(AuditObjectType.USER_ACCOUNT),
              eq(leaving.getId()),
              any(),
              any());
    }
  }

  @Test
  void aChangeOfTheMailChannelsIsToldToEveryAuditor() {
    auditorRoles.onMailConfigurationChanged(
        new MailConfigurationChangedEvent(organizationId, "die Einstellungen des E-Mail-Versands"));

    for (User auditor : List.of(existingAuditor, otherAuditor)) {
      verify(notifications)
          .notify(
              eq(organizationId),
              eq(auditor.getId()),
              eq(NotificationType.MAIL_CONFIGURATION_CHANGED),
              eq(AuditObjectType.SYSTEM_SETTING),
              isNull(),
              any(),
              argThat(body -> body.contains("E-Mail-Versands")));
    }
  }

  @Test
  void theCredentialsOfAnAuditorOrARequestedAccountStayWithItsPerson() {
    assertThatThrownBy(() -> auditorRoles.requireCredentialsWithPerson(existingAuditor, "X"))
        .isInstanceOf(ConflictException.class)
        .extracting(e -> ((ConflictException) e).getCode())
        .isEqualTo(AuditorRoles.AUDITOR_ACCOUNT);
    when(requests.findOpenByUserId(candidate.getId()))
        .thenReturn(
            Optional.of(
                new AuditorRoleRequest(
                    organizationId, candidate.getId(), UUID.randomUUID(), null, NOW)));
    assertThatThrownBy(() -> auditorRoles.requireCredentialsWithPerson(candidate, "X"))
        .isInstanceOf(ConflictException.class);

    User plain = account("plain@stadt.example", SystemRole.USER);
    auditorRoles.requireCredentialsWithPerson(plain, "X");
  }

  /**
   * The address of an identity provider's account is the provider's assertion, not the operations'.
   */
  @Test
  void onlyTheLocalAccountUnderTheConfiguredAddressNamesTheFirstAuditor() {
    User local = account("Erste.Revision@stadt.example", SystemRole.USER);
    when(credentials.existsById(local.getId())).thenReturn(true);
    User federated = account("erste.revision@stadt.example", SystemRole.USER);

    assertThat(auditorRoles.namesFirstAuditor(local)).isTrue();
    assertThat(auditorRoles.namesFirstAuditor(federated)).isFalse();
    assertThat(auditorRoles.namesFirstAuditor(candidate)).isFalse();
    assertThat(new AuditorRoleProperties(null).namesFirstAuditor("")).isFalse();
  }
}
