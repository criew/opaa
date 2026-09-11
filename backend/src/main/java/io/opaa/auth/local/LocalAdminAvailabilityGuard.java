package io.opaa.auth.local;

import io.opaa.api.types.ProviderType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcIssuerUris;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.ConflictException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that checks "never without a login-capable system administrator" (ADR-0033,
 * Entscheidung 4). Login capable is a {@code SYSTEM_ADMIN} that is either a local account whose
 * {@link LocalCredentials#isLoginCapable(Instant)} holds - the same rule the login and the token
 * validator apply, not a second formulation - or an account of an <em>enabled</em> OIDC provider
 * (in the {@code dev} mode: of the dev issuer, the trusted provider of ADR-0005). An administrator
 * of a disabled provider, a locked, expired or still invited local account does not count.
 *
 * <p>Every path that could remove the last such administrator runs through here under the advisory
 * lock {@link UserRepository#lockRoleChanges} of the organization, so two concurrent changes never
 * both count the other one's account as remaining: the token-driven and the manual role withdrawal,
 * disabling or deleting the last enabled OIDC provider, and - with #1537 - locking, expiring or
 * deleting a local administrator. The count happens under the held lock and the write is a
 * conditional {@code UPDATE}; the lock lasts for the rest of the caller's transaction. Refusals
 * carry {@value #ERROR_CODE}.
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

  private static final String DEV_MODE = "dev";

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final OidcProviderRepository providers;
  private final AuthProperties authProperties;
  private final Clock clock;

  public LocalAdminAvailabilityGuard(
      UserRepository users,
      LocalCredentialsRepository credentials,
      OidcProviderRepository providers,
      AuthProperties authProperties,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.providers = providers;
    this.authProperties = authProperties;
    this.clock = clock;
  }

  /**
   * Writes {@code target} over {@code SYSTEM_ADMIN} only while another login-capable administrator
   * of the organization remains; {@code 0} means refused - or the role was already moved by a
   * concurrent request, which the caller tells apart by re-reading under the still held lock.
   */
  @Transactional
  public int withdrawSystemAdminIfAnotherRemains(User user, SystemRole target) {
    users.lockRoleChanges(user.getOrganizationId());
    if (countLoginCapable(user.getOrganizationId(), user.getId(), null) == 0) {
      return 0;
    }
    return users.changeRoleIfStill(user.getId(), SystemRole.SYSTEM_ADMIN, target);
  }

  /**
   * Refuses (409 {@value #ERROR_CODE}) when no login-capable administrator other than {@code
   * excludedUserId} remains in the organization - the check before a manual role withdrawal and,
   * with #1537, before locking, expiring or deleting a local administrator.
   */
  @Transactional
  public void requireAnotherLoginCapableAdmin(UUID organizationId, UUID excludedUserId) {
    users.lockRoleChanges(organizationId);
    if (countLoginCapable(organizationId, excludedUserId, null) == 0) {
      throw new ConflictException(LAST_ADMIN_MESSAGE, ERROR_CODE);
    }
  }

  /**
   * Refuses (409 {@value #ERROR_CODE}) when, with the OIDC provider {@code providerId} gone or
   * disabled, no login-capable administrator would remain - the check before disabling or deleting
   * the last enabled provider (ADR-0033, Entscheidung 4).
   */
  @Transactional
  public void requireLoginCapableAdminWithoutProvider(UUID organizationId, UUID providerId) {
    users.lockRoleChanges(organizationId);
    if (countLoginCapable(organizationId, null, providerId) == 0) {
      throw new ConflictException(LOCAL_ADMIN_REQUIRED_MESSAGE, ERROR_CODE);
    }
  }

  /** How many login-capable administrators the organization has right now (no lock). */
  @Transactional(readOnly = true)
  public long countLoginCapableSystemAdmins(UUID organizationId) {
    return countLoginCapable(organizationId, null, null);
  }

  private long countLoginCapable(
      UUID organizationId, UUID excludedUserId, UUID excludedProviderId) {
    Instant now = clock.instant();
    List<OidcProvider> enabledProviders =
        providers.findAllByEnabledTrueOrderBySortOrderAscDisplayNameAsc().stream()
            .filter(provider -> provider.getProviderType() == ProviderType.OIDC)
            .filter(provider -> !provider.getId().equals(excludedProviderId))
            .toList();
    String devIssuer =
        DEV_MODE.equals(authProperties.mode()) ? authProperties.dev().issuer() : null;
    return users.findByOrganizationIdAndSystemRole(organizationId, SystemRole.SYSTEM_ADMIN).stream()
        .filter(admin -> !admin.getId().equals(excludedUserId))
        .filter(admin -> isLoginCapable(admin, enabledProviders, devIssuer, now))
        .count();
  }

  private boolean isLoginCapable(
      User admin, List<OidcProvider> enabledProviders, String devIssuer, Instant now) {
    if (LocalIssuer.URN.equals(admin.getIssuer())) {
      return credentials.findById(admin.getId()).map(row -> row.isLoginCapable(now)).orElse(false);
    }
    if (devIssuer != null && OidcIssuerUris.normalize(devIssuer).equals(normalize(admin))) {
      return true;
    }
    return enabledProviders.stream()
        .anyMatch(
            provider -> OidcIssuerUris.normalize(provider.getIssuerUri()).equals(normalize(admin)));
  }

  private static String normalize(User admin) {
    return OidcIssuerUris.normalize(admin.getIssuer());
  }
}
