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
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link TokenRoleSynchronizer} (#1331, ADR-0025 Entscheidung 4): the provider is authoritative for
 * {@code SYSTEM_ADMIN} and {@code AUDITOR} once it has a roles claim - written only on deviation,
 * audited under the identity-provider actor, {@code SYSTEM_ADMIN} before {@code AUDITOR}, and the
 * last administrator is never withdrawn by a token.
 */
class TokenRoleSynchronizerTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
  private final TokenRoleSynchronizer synchronizer =
      new TokenRoleSynchronizer(userRepository, auditEventRecorder);
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
    when(userRepository.withdrawSystemAdminIfAnotherRemains(any(), any())).thenReturn(1);
  }

  @Test
  void aTokenWithTheAdminRoleGrantsSystemAdminAndWritesTheGrantEvent() {
    User result = synchronizer.apply(user, provider, List.of("offline_access", "opaa-admin"));

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

    synchronizer.apply(user, provider, List.of("opaa-auditor"));

    verify(userRepository, never()).changeRoleIfStill(any(), any(), any());
    verify(userRepository, never()).withdrawSystemAdminIfAnotherRemains(any(), any());
    verify(auditEventRecorder, never()).recordSystemProcessAction(any());
  }

  @Test
  void aWithdrawalOfSystemAdminIsConditionalAndAuditedAsRevokedPlusGranted() {
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);

    User result = synchronizer.apply(user, provider, List.of("opaa-auditor"));

    assertThat(result.getSystemRole()).isEqualTo(SystemRole.AUDITOR);
    verify(userRepository).lockRoleChanges(user.getOrganizationId());
    verify(userRepository).withdrawSystemAdminIfAnotherRemains(user.getId(), SystemRole.AUDITOR);
    ArgumentCaptor<AuditEvent> audit = ArgumentCaptor.forClass(AuditEvent.class);
    verify(auditEventRecorder, times(2)).recordSystemProcessAction(audit.capture());
    assertThat(audit.getAllValues())
        .extracting(AuditEvent::eventType)
        .containsExactly(
            AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED, AuditEventType.AUDITOR_ROLE_GRANTED);
  }

  @Test
  void theLastSystemAdminKeepsTheRoleAndTheRefusalIsAudited() {
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(userRepository.withdrawSystemAdminIfAnotherRemains(any(), any())).thenReturn(0);

    User result = synchronizer.apply(user, provider, List.of());

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

    User result = synchronizer.apply(user, provider, List.of("opaa-admin"));

    assertThat(result).isSameAs(concurrent);
    verify(auditEventRecorder, never()).recordSystemProcessAction(any());
  }
}
