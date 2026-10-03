package io.opaa.auth;

import io.opaa.api.types.SystemRole;
import io.opaa.common.ConflictException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that checks "never without a login-capable system administrator" (ADR-0033,
 * Entscheidung 4). Login capable is a {@code SYSTEM_ADMIN} whose account {@link AccountUsability}
 * reports usable - inactivity is not asked here. An administrator of a disabled provider, a locked,
 * expired or still invited local account, and one the directory synchronisation locked does not
 * count.
 *
 * <p>Every path that could remove the last such administrator runs through here under the advisory
 * lock {@link UserRepository#lockRoleChanges} of the organization, so two concurrent changes never
 * both count the other one's account as remaining: the token-driven and the manual role withdrawal,
 * disabling or deleting the last enabled OIDC provider, and - with #1537 - locking, expiring or
 * deleting a local administrator. The count happens under the held lock and the write is a
 * conditional {@code UPDATE}; the lock lasts for the rest of the caller's transaction - which is
 * why the {@code require…} checks demand an existing transaction ({@code MANDATORY}): the caller's
 * own write must commit under the same lock, or the check protects nothing. Refusals carry {@value
 * #ERROR_CODE}.
 */
@Component
public class LocalAdminAvailabilityGuard {

  public static final String ERROR_CODE = "LAST_LOGIN_CAPABLE_ADMIN";

  static final String LAST_ADMIN_MESSAGE =
      "Der letzte anmeldefähige Systemverwalter kann nicht entfernt werden. Richten Sie zuerst ein"
          + " weiteres Systemverwalterkonto ein, das sich anmelden kann.";
  static final String LOCAL_ADMIN_REQUIRED_MESSAGE =
      "Ohne diesen Anbieter bliebe kein anmeldefähiger Systemverwalter übrig. Richten Sie zuerst"
          + " ein lokales Systemverwalterkonto mit Passwort ein.";

  private final UserRepository users;
  private final AccountUsability usability;

  public LocalAdminAvailabilityGuard(UserRepository users, AccountUsability usability) {
    this.users = users;
    this.usability = usability;
  }

  /**
   * Writes {@code target} over {@code SYSTEM_ADMIN} only while another login-capable administrator
   * of the organization remains; {@code 0} means refused - or the role was already moved by a
   * concurrent request, which the caller tells apart by re-reading under the still held lock.
   */
  @Transactional
  public int withdrawSystemAdminIfAnotherRemains(User user, SystemRole target) {
    users.lockRoleChanges(user.getOrganizationId());
    if (countLoginCapable(user.getOrganizationId(), Set.of(user.getId()), null) == 0) {
      return 0;
    }
    return users.changeRoleIfStill(user.getId(), SystemRole.SYSTEM_ADMIN, target);
  }

  /**
   * Refuses (409 {@value #ERROR_CODE}) when no login-capable administrator other than {@code
   * excludedUserId} remains in the organization - the check before a manual role withdrawal and,
   * with #1537, before locking, expiring or deleting a local administrator.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireAnotherLoginCapableAdmin(UUID organizationId, UUID excludedUserId) {
    requireLoginCapableAdminBesides(organizationId, Set.of(excludedUserId));
  }

  /**
   * The same check for an act that would take several administrators at once - the directory
   * synchronisation, which plans every lock of one run before applying any of them (#1818). Asking
   * once per account would let two departing administrators each count the other as remaining.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireLoginCapableAdminBesides(UUID organizationId, Set<UUID> excludedUserIds) {
    if (!hasLoginCapableAdminBesides(organizationId, excludedUserIds)) {
      throw new ConflictException(LAST_ADMIN_MESSAGE, ERROR_CODE);
    }
  }

  /**
   * The same rule as an answer instead of an exception - what a caller needs that <b>continues</b>
   * after a refusal, inside the transaction it shares with this guard. A {@code RuntimeException}
   * out of a participating {@code @Transactional} proxy marks the surrounding transaction
   * rollback-only ({@code globalRollbackOnParticipationFailure}), so catching it there would end
   * the whole caller at its commit; same reasoning as {@link #withdrawSystemAdminIfAnotherRemains},
   * which returns {@code 0} rather than throwing. Takes the advisory lock for the rest of the
   * caller's transaction, exactly like the throwing variants.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean hasLoginCapableAdminBesides(UUID organizationId, Set<UUID> excludedUserIds) {
    users.lockRoleChanges(organizationId);
    return countLoginCapable(organizationId, excludedUserIds, null) > 0;
  }

  /**
   * Refuses (409 {@value #ERROR_CODE}) when, with the OIDC provider {@code providerId} gone or
   * disabled, no login-capable administrator would remain - the check before disabling or deleting
   * the last enabled provider (ADR-0033, Entscheidung 4).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireLoginCapableAdminWithoutProvider(UUID organizationId, UUID providerId) {
    users.lockRoleChanges(organizationId);
    if (countLoginCapable(organizationId, Set.of(), providerId) == 0) {
      throw new ConflictException(LOCAL_ADMIN_REQUIRED_MESSAGE, ERROR_CODE);
    }
  }

  /** How many login-capable administrators the organization has right now (no lock). */
  @Transactional(readOnly = true)
  public long countLoginCapableSystemAdmins(UUID organizationId) {
    return countLoginCapable(organizationId, Set.of(), null);
  }

  private long countLoginCapable(
      UUID organizationId, Set<UUID> excludedUserIds, UUID excludedProviderId) {
    List<User> admins =
        users.findByOrganizationIdAndSystemRole(organizationId, SystemRole.SYSTEM_ADMIN).stream()
            .filter(admin -> !excludedUserIds.contains(admin.getId()))
            .toList();
    return usability.snapshotWithoutProvider(excludedProviderId).statesOf(admins).values().stream()
        .filter(AccountUsability.State::isUsable)
        .count();
  }
}
