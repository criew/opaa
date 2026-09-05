package io.opaa.auth;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Follows a provider's roles claim for {@code SYSTEM_ADMIN} and {@code AUDITOR} (ADR-0025,
 * Entscheidung 4): for a provider with a {@code roles_claim}, the token is authoritative - {@code
 * SYSTEM_ADMIN} before {@code AUDITOR} when a token carries both, {@code USER} when it carries
 * neither - and only a deviation from the stored role is written, as a role change with its audit
 * event under the {@value #IDENTITY_PROVIDER_ACTOR} system actor.
 *
 * <p>The last {@code SYSTEM_ADMIN} is never withdrawn by a token: the withdrawal is a conditional
 * {@code UPDATE} that writes only if another administrator remains, serialized per organization
 * through an advisory lock so two withdrawals cannot both count the other one as remaining. A
 * refused withdrawal is logged and audited ({@link
 * AuditEventType#SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED}); the account keeps the role the provider
 * withdrew. {@code AUDITOR} is not protected.
 */
@Component
public class TokenRoleSynchronizer {

  static final String IDENTITY_PROVIDER_ACTOR = "identity-provider";

  private static final Logger log = LoggerFactory.getLogger(TokenRoleSynchronizer.class);

  private final UserRepository userRepository;
  private final AuditEventRecorder auditEventRecorder;

  public TokenRoleSynchronizer(
      UserRepository userRepository, AuditEventRecorder auditEventRecorder) {
    this.userRepository = userRepository;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** The role a token's role values mean under {@code mapping}. */
  static SystemRole roleFor(OidcClaimMapping mapping, List<String> tokenRoles) {
    if (mapping.systemAdminRole() != null && tokenRoles.contains(mapping.systemAdminRole())) {
      return SystemRole.SYSTEM_ADMIN;
    }
    if (mapping.auditorRole() != null && tokenRoles.contains(mapping.auditorRole())) {
      return SystemRole.AUDITOR;
    }
    return SystemRole.USER;
  }

  /**
   * Aligns {@code user}'s stored role with what the token says; returns the user with the role it
   * has after this call. No write when nothing changed.
   */
  @Transactional
  public User apply(User user, OidcProvider provider, List<String> tokenRoles) {
    SystemRole target = roleFor(provider.getClaimMapping(), tokenRoles);
    SystemRole current = user.getSystemRole();
    if (target == current) {
      return user;
    }
    userRepository.lockRoleChanges(user.getOrganizationId());
    int written =
        current == SystemRole.SYSTEM_ADMIN
            ? userRepository.withdrawSystemAdminIfAnotherRemains(user.getId(), target)
            : userRepository.changeRoleIfStill(user.getId(), current, target);
    if (written == 0) {
      if (current == SystemRole.SYSTEM_ADMIN) {
        refuseWithdrawal(user, provider, target);
        return user;
      }
      // a concurrent request already moved the role - read what it wrote
      return userRepository.findById(user.getId()).orElse(user);
    }
    user.setSystemRole(target);
    recordChange(user, provider, current, target);
    return user;
  }

  private void refuseWithdrawal(User user, OidcProvider provider, SystemRole target) {
    log.warn(
        "Provider '{}' withdrew SYSTEM_ADMIN from the last system administrator (user {}); the"
            + " withdrawal is refused so the installation keeps one. Assign the role in the"
            + " provider or clear roles_claim on the provider.",
        provider.getDisplayName(),
        user.getId());
    UUID pseudonym = auditEventRecorder.pseudonymFor(user.getId(), user.getOrganizationId());
    auditEventRecorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actorRef(IDENTITY_PROVIDER_ACTOR)
            .type(AuditEventType.SYSTEM_ADMIN_ROLE_REVOCATION_REFUSED)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .before(Map.of("role", SystemRole.SYSTEM_ADMIN.name()))
            .after(Map.of("role", SystemRole.SYSTEM_ADMIN.name(), "tokenRole", target.name()))
            .outcome(AuditOutcome.DENIED)
            .reason("Letzter Systemverwalter: Entzug durch " + provider.getDisplayName())
            .build());
  }

  /** One event per elevated role left and one per elevated role entered - as in manual changes. */
  private void recordChange(User user, OidcProvider provider, SystemRole from, SystemRole to) {
    UUID pseudonym = auditEventRecorder.pseudonymFor(user.getId(), user.getOrganizationId());
    if (from == SystemRole.SYSTEM_ADMIN) {
      record(user, provider, pseudonym, AuditEventType.SYSTEM_ADMIN_ROLE_REVOKED, from, to);
    } else if (from == SystemRole.AUDITOR) {
      record(user, provider, pseudonym, AuditEventType.AUDITOR_ROLE_REVOKED, from, to);
    }
    if (to == SystemRole.SYSTEM_ADMIN) {
      record(user, provider, pseudonym, AuditEventType.SYSTEM_ADMIN_ROLE_GRANTED, from, to);
    } else if (to == SystemRole.AUDITOR) {
      record(user, provider, pseudonym, AuditEventType.AUDITOR_ROLE_GRANTED, from, to);
    }
  }

  private void record(
      User user,
      OidcProvider provider,
      UUID pseudonym,
      AuditEventType type,
      SystemRole from,
      SystemRole to) {
    auditEventRecorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actorRef(IDENTITY_PROVIDER_ACTOR)
            .type(type)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .before(Map.of("role", from.name()))
            .after(Map.of("role", to.name(), "provider", provider.getDisplayName()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
