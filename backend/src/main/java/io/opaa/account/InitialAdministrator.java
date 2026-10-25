package io.opaa.account;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.PasswordChangeReason;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the local emergency account of the system administration (ADR-0033, Entscheidung 5): from
 * the installation (ADR-0060, Entscheidung 8) with the password hash it took, or from {@link
 * LocalAdminReset} on an installation without one. Live, with the LOCAL provider row it signs in
 * through; the change of password at the first sign-in is forced when the caller asks for it. Runs
 * inside the caller's transaction only.
 */
@Component
public class InitialAdministrator {

  public static final String LOCAL_PROVIDER_DISPLAY_NAME = "Lokale Konten";
  public static final String CREATED_REASON = "Notanker-Konto der Systemverwaltung";

  /** The address of {@code dev-admin}: a development value, never a mailbox. */
  public static final String REJECTED_DEFAULT_EMAIL = "admin@opaa.local";

  private static final int MAX_EMAIL_LENGTH = 320;

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final OidcProviderRepository providers;
  private final AuditEventRecorder audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public InitialAdministrator(
      UserRepository users,
      LocalCredentialsRepository credentials,
      OidcProviderRepository providers,
      AuditEventRecorder audit,
      ApplicationEventPublisher events,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.providers = providers;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  /**
   * The rule a present address of the emergency account follows, wherever it comes from.
   *
   * @return the problem in words for the person setting it up, empty when the address is usable
   */
  public static Optional<String> addressProblem(String email) {
    if (!looksLikeAnAddress(email) || email.length() > MAX_EMAIL_LENGTH) {
      return Optional.of("Das ist keine gültige E-Mail-Adresse.");
    }
    if (REJECTED_DEFAULT_EMAIL.equalsIgnoreCase(email)) {
      return Optional.of(
          "Bitte ein zustellbares Postfach angeben, etwa ein Funktionspostfach der IT.");
    }
    return Optional.empty();
  }

  /** Whether an emergency account or a local account under the address exists already. */
  public boolean conflictsWith(String email) {
    return credentials.findByBootstrapTrue().isPresent()
        || users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email).isPresent();
  }

  /**
   * Creates the account of the installation and returns it.
   *
   * @throws ConflictException when an emergency account or a local account with the address exists
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public User create(
      String email,
      String displayName,
      String passwordHash,
      String actorRef,
      boolean passwordChangeRequired) {
    User admin = createAccount(email, displayName, passwordHash, passwordChangeRequired);
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

  /**
   * Creates the account for {@link LocalAdminReset}, the change of password forced; the caller
   * records the audit event and publishes the provider change.
   *
   * @throws ConflictException when an emergency account or a local account with the address exists
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public User createForReset(String email, String displayName, String passwordHash) {
    return createAccount(email, displayName, passwordHash, true);
  }

  private User createAccount(
      String email, String displayName, String passwordHash, boolean passwordChangeRequired) {
    if (conflictsWith(email)) {
      throw new ConflictException(
          "Es gibt bereits ein Notanker-Konto oder ein lokales Konto unter dieser Adresse.");
    }
    Instant now = clock.instant();
    ensureLocalProviderRow();
    User admin = createBootstrapUser(email, displayName);
    LocalCredentials row = new LocalCredentials(admin.getId(), CREATED_REASON, now);
    row.markBootstrap();
    row.markEmailVerified(now);
    row.setPasswordHash(passwordHash, now);
    if (passwordChangeRequired) {
      row.requirePasswordChange(PasswordChangeReason.INITIAL, now);
    } else {
      row.clearPasswordChangeRequirement(now);
    }
    credentials.save(row);
    return admin;
  }

  private static boolean looksLikeAnAddress(String email) {
    int at = email.indexOf('@');
    return at > 0
        && at < email.length() - 1
        && email.indexOf('@', at + 1) < 0
        && email.chars().noneMatch(Character::isWhitespace);
  }

  /** The LOCAL provider row the local accounts sign in through, created switched off. */
  private void ensureLocalProviderRow() {
    if (providers.findLocalRow().isEmpty()) {
      providers.save(OidcProvider.localProvider(LOCAL_PROVIDER_DISPLAY_NAME));
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
