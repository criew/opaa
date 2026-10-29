package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link TokenRoleSynchronizer} (ADR-0025 Entscheidung 4): the provider is authoritative for {@code
 * SYSTEM_ADMIN} and {@code AUDITOR} once it has a roles claim - written only on deviation, audited
 * under the identity-provider actor, {@code SYSTEM_ADMIN} before {@code AUDITOR}, and the last
 * login-capable administrator is never withdrawn by a token - the count is the {@link
 * LocalAdminAvailabilityGuard}'s (ADR-0033, Entscheidung 4).
 */
class TokenRoleSynchronizerTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final LocalAdminAvailabilityGuard guard = mock(LocalAdminAvailabilityGuard.class);
  private final AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
  private final AuditorRoles auditorRoles = mock(AuditorRoles.class);
  private final List<Object> events = new ArrayList<>();
  private final TokenRoleSynchronizer synchronizer =
      new TokenRoleSynchronizer(
          userRepository, guard, auditEventRecorder, events::add, auditorRoles);
  private final OidcProvider provider =
      new OidcProvider(
          "Beschäftigte",
          "https://idp.example/realms/a",
          "opaa-frontend",
          null,
          new OidcClaimMapping(
              null, null, "realm_access.roles", "opaa-admin", "opaa-auditor", null));

  private User user;

  @BeforeEach
  void setUp() {
    user = new User("alice", "https://idp.example/realms/a", "alice@behoerde.example", "Alice");
    user.setOrganizationId(UUID.randomUUID());
    when(auditEventRecorder.pseudonymFor(any(), any())).thenReturn(UUID.randomUUID());
    when(userRepository.changeRoleIfStill(any(), any(), any())).thenReturn(1);
    when(guard.withdrawSystemAdminIfAnotherRemains(any(), any())).thenReturn(1);
  }

  @Test
  void aTokenWithTheAdminRoleGrantsSystemAdminAndWritesTheGrantEvent() {
    User result =
        synchronizer.apply(
            user, provider, TokenRoles.named(List.of("offline_access", "opaa-admin")));

    assertThat(result.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    verify(userRepository)
        .changeRoleIfStill(user.getId(), SystemRole.USER, SystemRole.SYSTEM_ADMIN);
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordSystemProcessAction(audit.capture());
    assertThat(audit.getValue().eventType()).isEqualTo(AuditEventType.SYSTEM_ADMIN_ROLE_GRANTED);
    assertThat(audit.getValue().actorRef()).isEqualTo("identity-provider");
  }

  @Test
  void systemAdminWinsOverAuditorWhenATokenCarriesBoth() {
    assertThat(
            TokenRoleSynchronizer.roleFor(
                provider.getClaimMapping(), List.of("opaa-auditor", "opaa-admin")))
        .isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(TokenRoleSynchronizer.roleFor(provider.getClaimMapping(), List.of("opaa-auditor")))
        .isEqualTo(SystemRole.AUDITOR);
    assertThat(TokenRoleSynchronizer.roleFor(provider.getClaimMapping(), List.of()))
        .isEqualTo(SystemRole.USER);
  }

  @Test
  void anUnchangedRoleWritesNothing() {
    user.setSystemRole(SystemRole.AUDITOR);

    synchronizer.apply(user, provider, TokenRoles.named(List.of("opaa-auditor")));

    verify(userRepository, never()).changeRoleIfStill(any(), any(), any());
    verify(guard, never()).withdrawSystemAdminIfAnotherRemains(any(), any());
    verify(auditEventRecorder, never()).recordSystemProcessAction(any());
  }

  /**
   * Regression guard for #3356: an administrator moved to AUDITOR by the claim loses SYSTEM_ADMIN
   * at once, conditionally, but receives AUDITOR only through an auditor's confirmation.
   */
  @Test
  void aClaimMovingAnAdministratorToAuditorWithdrawsAdminAndOnlyAsksForAuditor() {
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(auditorRoles.requestFromProvider(user, provider)).thenReturn(true);

    User result = synchronizer.apply(user, provider, TokenRoles.named(List.of("opaa-auditor")));

    assertThat(result.getSystemRole()).isEqualTo(SystemRole.USER);
    // the guard is the one place that counts login-capable administrators (ADR-0033, 4)
    verify(guard).withdrawSystemAdminIfAnotherRemains(user, SystemRole.USER);
    verify(auditorRoles).requestFromProvider(user, provider);
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder, times(1)).recordSystemProcessAction(audit.capture());
    assertThat(audit.getAllValues())
        .extracting(AuditEvent::eventType)
        .containsExactly(AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED);
  }

  /** Regression guard for #3356: the claim alone never makes an account an auditor. */
  @Test
  void aClaimNamingAuditorOpensARequestAndLeavesTheRole() {
    when(auditorRoles.requestFromProvider(user, provider)).thenReturn(true);

    User result = synchronizer.apply(user, provider, TokenRoles.named(List.of("opaa-auditor")));

    assertThat(result.getSystemRole()).isEqualTo(SystemRole.USER);
    verify(auditorRoles).requestFromProvider(user, provider);
    verify(userRepository, never()).changeRoleIfStill(any(), any(), any());
    verify(auditEventRecorder, never()).recordSystemProcessAction(any());
    assertThat(events).isEmpty();
  }

  @Test
  void withoutAnAuditorTheClaimGrantsNothing() {
    when(auditorRoles.requestFromProvider(user, provider)).thenReturn(false);

    User result = synchronizer.apply(user, provider, TokenRoles.named(List.of("opaa-auditor")));

    assertThat(result.getSystemRole()).isEqualTo(SystemRole.USER);
    verify(userRepository, never()).changeRoleIfStill(any(), any(), any());
  }

  @Test
  void aClaimNoLongerNamingAuditorWithdrawsItTellsTheAuditorsAndEndsTheRequest() {
    user.setSystemRole(SystemRole.AUDITOR);

    User result = synchronizer.apply(user, provider, TokenRoles.named(List.of()));

    assertThat(result.getSystemRole()).isEqualTo(SystemRole.USER);
    verify(auditorRoles).endProviderRequest(user, provider);
    assertThat(events).singleElement().isInstanceOf(AuditorRoleRevokedEvent.class);
  }

  @Test
  void theLastSystemAdminKeepsTheRoleAndTheRefusalIsAudited() {
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(guard.withdrawSystemAdminIfAnotherRemains(any(), any())).thenReturn(0);

    User result = synchronizer.apply(user, provider, TokenRoles.named(List.of()));

    assertThat(result.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder).recordSystemProcessAction(audit.capture());
    assertThat(audit.getValue().eventType())
        .isEqualTo(AuditEventType.SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED);
    assertThat(audit.getValue().outcome()).isEqualTo(AuditOutcome.DENIED);
  }

  @Test
  void aRoleAnotherRequestAlreadyMovedIsReadBackInsteadOfOverwritten() {
    when(userRepository.changeRoleIfStill(any(), any(), any())).thenReturn(0);
    User concurrent = new User("alice", "https://idp.example/realms/a", null, null);
    concurrent.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(userRepository.findById(user.getId())).thenReturn(Optional.of(concurrent));

    User result = synchronizer.apply(user, provider, TokenRoles.named(List.of("opaa-admin")));

    assertThat(result).isSameAs(concurrent);
    verify(auditEventRecorder, never()).recordSystemProcessAction(any());
  }
}
