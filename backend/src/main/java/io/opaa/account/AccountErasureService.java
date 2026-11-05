package io.opaa.account;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes the personal reference of a deactivated account on a request under Art. 17 GDPR, local or
 * of an identity provider, irreversibly. The {@code users} row stays as a nameless shell ({@link
 * User#erase}) so the rights history and the audit trail keep valid references (ADR-0016); what the
 * person holds goes in the same transaction: here the credentials, sessions, tokens, notifications
 * and the usage counter of the identity module, elsewhere through the listeners of {@link
 * AccountErasureEvent}. Memberships, grants and shared objects stay with the shell.
 *
 * <p>Only a deactivated account ({@link AccountUsability.State#DEACTIVATED}) without a system role
 * is erased, never the caller's own, and none an open incident scope names - its evidence must keep
 * its person until that scope ends.
 */
@Service
public class AccountErasureService {

  static final String SELF_ERASE = "SELF_ERASE";
  static final String ACCOUNT_ACTIVE = "ACCOUNT_ACTIVE";
  static final String SYSTEM_ROLE_HELD = "SYSTEM_ROLE_HELD";
  static final String INCIDENT_SCOPE_OPEN = "INCIDENT_SCOPE_OPEN";

  private static final Logger log = LoggerFactory.getLogger(AccountErasureService.class);

  private final UserRepository users;
  private final AccountUsability usability;
  private final AuditEventRecorder audit;
  private final ApplicationEventPublisher events;
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public AccountErasureService(
      UserRepository users,
      AccountUsability usability,
      AuditEventRecorder audit,
      ApplicationEventPublisher events,
      JdbcTemplate jdbc,
      Clock clock) {
    this.users = users;
    this.usability = usability;
    this.audit = audit;
    this.events = events;
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /**
   * Erases account {@code userId} of the caller's organization.
   *
   * @throws NotFoundException for an account of another organization or one already erased
   * @throws ConflictException with {@link #SELF_ERASE}, {@link #ACCOUNT_ACTIVE}, {@link
   *     #SYSTEM_ROLE_HELD} or {@link #INCIDENT_SCOPE_OPEN}, or from a listener of {@link
   *     AccountErasureEvent}; nothing is written then
   */
  @Transactional
  public void erase(CurrentUser actor, UUID userId) {
    lockRows(userId);
    User user =
        users
            .findByIdAndOrganizationId(userId, actor.organizationId())
            .filter(found -> !found.isErased())
            .orElseThrow(() -> new NotFoundException("Konto nicht gefunden"));
    if (userId.equals(actor.id())) {
      throw new ConflictException(
          "Den Personenbezug des eigenen Kontos können Sie nicht entfernen.", SELF_ERASE);
    }
    if (user.getSystemRole() != SystemRole.USER) {
      throw new ConflictException(
          "Das Konto hat noch eine Systemrolle. Entziehen Sie sie zuerst.", SYSTEM_ROLE_HELD);
    }
    if (usability.stateOf(user) != AccountUsability.State.DEACTIVATED) {
      throw new ConflictException(
          "Das Konto ist noch nicht deaktiviert. Sperren Sie es zuerst.", ACCOUNT_ACTIVE);
    }
    Instant now = clock.instant();
    if (namedByOpenIncidentScope(userId, now)) {
      throw new ConflictException(
          "Eine offene anlassbezogene Klärung nennt das Konto. Der Personenbezug bleibt, bis sie"
              + " endet.",
          INCIDENT_SCOPE_OPEN);
    }
    recordErasure(actor, user);
    events.publishEvent(new AccountErasureEvent(actor, user, now));
    deleteIdentityData(userId);
    // a listener's bulk update may have cleared the persistence context; read the row afresh
    User shell = users.findById(userId).orElseThrow();
    shell.erase(now);
    users.saveAndFlush(shell);
    log.info("Personal reference of account {} removed", userId);
  }

  /** The account and its credentials, locked against a concurrent unlock or role change. */
  private void lockRows(UUID userId) {
    jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
    jdbc.queryForList(
        "SELECT user_id FROM local_credentials WHERE user_id = ? FOR UPDATE", UUID.class, userId);
  }

  /** The rule of the locked-account chat deletion: a pending request or a grant still usable. */
  private boolean namedByOpenIncidentScope(UUID userId, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM audit_incident_scope_grants g"
                + " WHERE (g.subject_user_id = ? OR g.requested_by_user_id = ?"
                + " OR g.approved_by_user_id = ?)"
                + " AND (g.status = 'PENDING' OR g.usable_until >= ?))",
            Boolean.class,
            userId,
            userId,
            userId,
            Timestamp.from(now)));
  }

  private void deleteIdentityData(UUID userId) {
    jdbc.update("DELETE FROM local_refresh_tokens WHERE user_id = ?", userId);
    jdbc.update("DELETE FROM local_revoked_tokens WHERE user_id = ?", userId);
    jdbc.update("DELETE FROM local_action_tokens WHERE user_id = ?", userId);
    jdbc.update("DELETE FROM local_credentials WHERE user_id = ?", userId);
    jdbc.update("DELETE FROM notifications WHERE recipient_user_id = ?", userId);
    jdbc.update("DELETE FROM self_disclosure_usage WHERE account_id = ?", userId);
    jdbc.update("DELETE FROM auditor_role_requests WHERE user_id = ?", userId);
  }

  private void recordErasure(CurrentUser actor, User user) {
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("origin", LocalIssuer.URN.equals(user.getIssuer()) ? "LOCAL" : "PROVIDER");
    UUID pseudonym = audit.pseudonymFor(user.getId(), user.getOrganizationId());
    audit.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actor(actor.id())
            .type(AuditEventType.ACCOUNT_ERASED)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .before(before)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
