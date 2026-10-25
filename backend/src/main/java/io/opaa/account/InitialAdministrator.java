package io.opaa.account;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.OidcProvidersChangedEvent;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the local emergency account of the system administration (ADR-0033, Entscheidung 5) from
 * the installation (ADR-0060, Entscheidung 8): live, with the password hash the installation took,
 * no forced change, and the LOCAL provider row it signs in through. The seed marker is written with
 * it, so no start-up value is taken over afterwards. Runs inside the caller's transaction only.
 */
@Component
public class InitialAdministrator {

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final OidcProviderRepository providers;
  private final LocalAdminSeedMarkerRepository marker;
  private final AuditEventRecorder audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public InitialAdministrator(
      UserRepository users,
      LocalCredentialsRepository credentials,
      OidcProviderRepository providers,
      LocalAdminSeedMarkerRepository marker,
      AuditEventRecorder audit,
      ApplicationEventPublisher events,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.providers = providers;
    this.marker = marker;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  /**
   * Creates the account and returns it.
   *
   * @throws ConflictException when an emergency account or a local account with the address exists
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public User create(String email, String displayName, String passwordHash, String actorRef) {
    if (credentials.findByBootstrapTrue().isPresent()) {
      throw new ConflictException("Es gibt bereits ein Notanker-Konto der Systemverwaltung.");
    }
    if (users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email).isPresent()) {
      throw new ConflictException("Unter dieser Adresse gibt es bereits ein lokales Konto.");
    }
    Instant now = clock.instant();
    ensureLocalProviderRow();
    User admin = createBootstrapUser(email, displayName);
    LocalCredentials row =
        new LocalCredentials(admin.getId(), LocalAdminSeeder.CREATED_REASON, now);
    row.markBootstrap();
    row.markEmailVerified(now);
    row.setPasswordHash(passwordHash, now);
    row.clearPasswordChangeRequirement(now);
    credentials.save(row);
    if (!marker.seedAlreadyAttempted()) {
      marker.save(new LocalAdminSeedMarker(now));
    }
    UUID pseudonym = audit.pseudonymFor(admin.getId(), admin.getOrganizationId());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(admin.getOrganizationId())
            .actorRef(actorRef)
            .type(AuditEventType.LOCAL_ADMIN_SEEDED)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, admin.getId())
            .after(Map.of("state", "ACTIVE", "passwordSource", "INSTALLATION"))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    events.publishEvent(new OidcProvidersChangedEvent());
    return admin;
  }

  /** The LOCAL provider row the local accounts sign in through, created switched off. */
  private void ensureLocalProviderRow() {
    if (providers.findLocalRow().isEmpty()) {
      providers.save(OidcProvider.localProvider(LocalAdminSeeder.LOCAL_PROVIDER_DISPLAY_NAME));
    }
  }

  /** A {@code SYSTEM_ADMIN} account of the local issuer, flushed for the audit pseudonym. */
  private User createBootstrapUser(String email, String displayName) {
    User admin = User.localAccount(email, displayName);
    admin.setOrganizationId(Organization.DEFAULT_ID);
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    return users.saveAndFlush(admin);
  }
}
