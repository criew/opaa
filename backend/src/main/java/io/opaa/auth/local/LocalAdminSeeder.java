package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.auth.oidc.OidcProvidersChangedEvent;
import io.opaa.organization.Organization;
import io.opaa.security.PasswordGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the bootstrap system administrator and the LOCAL provider row on the first start in the
 * {@code oidc} mode (ADR-0033, Entscheidung 5). Guarded by {@link LocalAdminSeedMarker}, never by
 * "does the account exist?": a deleted account stays deleted until {@code
 * OPAA_LOCAL_ADMIN_RESET=force} restores it. Two cases of the first start:
 *
 * <ul>
 *   <li><b>Fresh installation</b> - no account exists yet: the account is live with {@code
 *       OPAA_INITIAL_ADMIN_PASSWORD} (used as is, checked against the password policy, no forced
 *       change) or with a generated password that is written <em>once</em> as a clearly marked
 *       block into the application log, with the change forced at the first sign-in.
 *   <li><b>Existing installation</b> - {@code users} is not empty: the account is created {@code
 *       INVITED}, without a password and without any log block; the operator activates it
 *       deliberately with the forced restart. "No account" rather than "no provider takeover
 *       marker" is the criterion, because that marker is written in the same start even when this
 *       seed refused the address.
 * </ul>
 *
 * <p>The shipped default {@code admin@opaa.local} is a dev-mode value, not a mailbox: it and a
 * blank or malformed address are refused with an error naming {@value
 * #INITIAL_ADMIN_EMAIL_VARIABLE}, nothing is written and the next start tries again (the pattern of
 * the missing OIDC bootstrap in ADR-0025). Nothing happens in the {@code dev} mode, not even the
 * marker. Audit: {@code LOCAL_ADMIN_SEEDED} and {@code LOCAL_ADMIN_RESET} under the {@code
 * local-auth} system actor, never with the address or the password in the payload.
 */
@Component
public class LocalAdminSeeder {

  public static final String DISPLAY_NAME = "Systemverwaltung";
  public static final String LOCAL_PROVIDER_DISPLAY_NAME = "Lokale Konten";
  public static final String CREATED_REASON = "Notanker-Konto der Systemverwaltung";
  public static final String REJECTED_DEFAULT_EMAIL = "admin@opaa.local";
  public static final String INITIAL_ADMIN_EMAIL_VARIABLE = "OPAA_INITIAL_ADMIN_EMAIL";

  private static final Logger log = LoggerFactory.getLogger(LocalAdminSeeder.class);
  private static final String OIDC_MODE = "oidc";
  private static final String RULE =
      "=====================================================================";

  /** What a run did - for the runner's log and the tests. */
  public enum Outcome {
    /** Not the {@code oidc} mode, already seeded, or only the marker was added to existing rows. */
    SKIPPED,
    /** The configured address was refused; nothing written, the next start tries again. */
    REJECTED,
    /** Fresh installation: live account with a password. */
    SEEDED_ACTIVE,
    /** Existing installation: {@code INVITED} account without a password. */
    SEEDED_INVITED,
    /** {@code OPAA_LOCAL_ADMIN_RESET=force}: the account was restored or recreated. */
    RESET
  }

  private final AuthProperties authProperties;
  private final LocalAuthProperties localAuthProperties;
  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final LocalRefreshTokenRepository refreshTokens;
  private final OidcProviderRepository providers;
  private final LocalAdminSeedMarkerRepository marker;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;
  private final PasswordGenerator passwordGenerator;
  private final AuditEventRecorder audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public LocalAdminSeeder(
      AuthProperties authProperties,
      LocalAuthProperties localAuthProperties,
      UserRepository users,
      LocalCredentialsRepository credentials,
      LocalRefreshTokenRepository refreshTokens,
      OidcProviderRepository providers,
      LocalAdminSeedMarkerRepository marker,
      PasswordEncoder passwordEncoder,
      PasswordPolicy passwordPolicy,
      PasswordGenerator passwordGenerator,
      AuditEventRecorder audit,
      ApplicationEventPublisher events,
      Clock clock) {
    this.authProperties = authProperties;
    this.localAuthProperties = localAuthProperties;
    this.users = users;
    this.credentials = credentials;
    this.refreshTokens = refreshTokens;
    this.providers = providers;
    this.marker = marker;
    this.passwordEncoder = passwordEncoder;
    this.passwordPolicy = passwordPolicy;
    this.passwordGenerator = passwordGenerator;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  @Transactional
  public Outcome seedIfNeeded() {
    if (!OIDC_MODE.equals(authProperties.mode())) {
      return Outcome.SKIPPED;
    }
    if (localAuthProperties.isAdminResetForced()) {
      return restoreBootstrapAdmin();
    }
    if (marker.seedAlreadyAttempted()) {
      return Outcome.SKIPPED;
    }
    if (credentials.findByBootstrapTrue().isPresent()) {
      ensureLocalProviderRow();
      marker.save(new LocalAdminSeedMarker(clock.instant()));
      log.info("Bootstrap administrator already exists; the seed marker is added without seeding");
      return Outcome.SKIPPED;
    }
    String email = requireInitialAdminEmail();
    if (email == null) {
      return Outcome.REJECTED;
    }
    if (users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email).isPresent()) {
      log.error(
          "Kein Notanker-Konto angelegt: Unter der Adresse aus {} existiert bereits ein anderes"
              + " lokales Konto. Eine andere Adresse setzen und neu starten - die Anlage wird"
              + " dann nachgeholt.",
          INITIAL_ADMIN_EMAIL_VARIABLE);
      return Outcome.REJECTED;
    }
    Instant now = clock.instant();
    // a fresh installation is one without accounts - not "without the provider takeover marker":
    // that marker is written in the same start even when this seed refused the address, and the
    // corrected address on the next start must still yield a live account
    boolean fresh = users.count() == 0;
    if (fresh && !environmentPasswordAcceptable(email)) {
      return Outcome.REJECTED;
    }
    ensureLocalProviderRow();
    User admin = createBootstrapUser(email);
    LocalCredentials row = new LocalCredentials(admin.getId(), CREATED_REASON, now);
    row.markBootstrap();
    row.markEmailVerified(now);
    String generated = fresh ? applyPassword(row, now) : null;
    credentials.save(row);
    marker.save(new LocalAdminSeedMarker(now));
    recordAudit(
        admin,
        AuditEventType.LOCAL_ADMIN_SEEDED,
        Map.of(
            "state",
            fresh ? "ACTIVE" : "INVITED",
            "passwordSource",
            passwordSource(fresh, generated)));
    events.publishEvent(new OidcProvidersChangedEvent());
    if (!fresh) {
      log.info(
          "Notanker-Konto der Systemverwaltung als INVITED angelegt (Bestandsinstallation, kein"
              + " Passwort). Aktivierung bei Bedarf mit {}=force und Neustart, siehe"
              + " docs/handbuch/deployment.md.",
          LocalAuthProperties.ADMIN_RESET_VARIABLE);
      return Outcome.SEEDED_INVITED;
    }
    if (generated != null) {
      printPasswordBlock(generated);
    }
    log.info(
        "Notanker-Konto der Systemverwaltung angelegt (Konto {}); lokale Konten sind deaktiviert,"
            + " bis die Systemverwaltung sie einschaltet.",
        admin.getId());
    return Outcome.SEEDED_ACTIVE;
  }

  /**
   * {@code OPAA_LOCAL_ADMIN_RESET=force}: restores a login-capable bootstrap administrator once -
   * unlocked, expiry cleared, new password ({@code OPAA_INITIAL_ADMIN_PASSWORD} or a generated one
   * printed once), {@code SYSTEM_ADMIN} restored, every session of the account ended; a deleted
   * account is recreated with the configured address. Audited as {@code LOCAL_ADMIN_RESET}.
   */
  @Transactional
  public Outcome restoreBootstrapAdmin() {
    Instant now = clock.instant();
    Optional<LocalCredentials> existing = credentials.findByBootstrapTrue();
    User admin;
    LocalCredentials row;
    boolean recreated = existing.isEmpty();
    // the environment password is checked before any mutation: a refusal must leave the account
    // exactly as it was (still locked, old password, no revocation) - the transaction would
    // otherwise commit the partial restore through dirty checking
    if (existing.isPresent()) {
      row = existing.get();
      admin = users.findById(row.getUserId()).orElseThrow();
      if (!environmentPasswordAcceptable(admin.getEmail())) {
        return Outcome.REJECTED;
      }
      row.unlock(now);
      row.setExpiresAt(null, now);
      if (row.getEmailVerifiedAt() == null) {
        row.markEmailVerified(now);
      }
      if (admin.getSystemRole() != SystemRole.SYSTEM_ADMIN) {
        admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
        users.save(admin);
      }
    } else {
      String email = requireInitialAdminEmail();
      if (email == null) {
        return Outcome.REJECTED;
      }
      if (!environmentPasswordAcceptable(email)) {
        return Outcome.REJECTED;
      }
      if (users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email).isPresent()) {
        log.error(
            "{}=force: Kein Notanker-Konto angelegt - unter der Adresse aus {} existiert bereits"
                + " ein anderes lokales Konto.",
            LocalAuthProperties.ADMIN_RESET_VARIABLE,
            INITIAL_ADMIN_EMAIL_VARIABLE);
        return Outcome.REJECTED;
      }
      admin = createBootstrapUser(email);
      row = new LocalCredentials(admin.getId(), CREATED_REASON, now);
      row.markBootstrap();
      row.markEmailVerified(now);
    }
    ensureLocalProviderRow();
    String generated = applyPassword(row, now);
    row.invalidateSessionsIssuedBefore(LocalTokenRevocationService.cutoffFor(now), now);
    credentials.save(row);
    refreshTokens.revokeAllForUser(admin.getId(), RevocationReason.ADMIN, now);
    if (!marker.seedAlreadyAttempted()) {
      marker.save(new LocalAdminSeedMarker(now));
    }
    recordAudit(
        admin,
        AuditEventType.LOCAL_ADMIN_RESET,
        Map.of("recreated", recreated, "passwordSource", passwordSource(true, generated)));
    events.publishEvent(new OidcProvidersChangedEvent());
    if (generated != null) {
      printPasswordBlock(generated);
    }
    log.warn(
        "{}=force: Notanker-Konto der Systemverwaltung {} (Konto {}), alle seine Sitzungen beendet."
            + " Die Variable jetzt wieder entfernen - jeder weitere Start würde das Konto erneut"
            + " zurücksetzen.",
        LocalAuthProperties.ADMIN_RESET_VARIABLE,
        recreated ? "neu angelegt" : "wiederhergestellt",
        admin.getId());
    return Outcome.RESET;
  }

  /** The address exactly as configured (trimmed), or {@code null} with the operator told why. */
  private String requireInitialAdminEmail() {
    String email =
        authProperties.initialAdminEmail() == null ? "" : authProperties.initialAdminEmail().trim();
    String problem = null;
    if (email.isEmpty()) {
      problem = INITIAL_ADMIN_EMAIL_VARIABLE + " ist nicht gesetzt";
    } else if (REJECTED_DEFAULT_EMAIL.equalsIgnoreCase(email)) {
      problem =
          "der ausgelieferte Vorgabewert "
              + REJECTED_DEFAULT_EMAIL
              + " ist kein Anmeldename und kein zustellbares Postfach";
    } else if (!looksLikeAnAddress(email)) {
      problem = "der Wert von " + INITIAL_ADMIN_EMAIL_VARIABLE + " ist keine E-Mail-Adresse";
    }
    if (problem == null) {
      return email;
    }
    log.error(
        "Kein Notanker-Konto der Systemverwaltung angelegt ({}): Bis es existiert, ist keine"
            + " lokale Anmeldung als Systemverwalter möglich. {} auf ein zustellbares Postfach"
            + " setzen (am besten ein Funktionspostfach der IT) und neu starten - die Anlage wird"
            + " dann nachgeholt. Siehe docs/handbuch/deployment.md.",
        problem,
        INITIAL_ADMIN_EMAIL_VARIABLE);
    return null;
  }

  /**
   * {@code OPAA_INITIAL_ADMIN_PASSWORD}, if set, must satisfy the {@link PasswordPolicy} like any
   * password: BCrypt refuses more than 72 bytes with an exception that would abort every start
   * without naming the variable, and a three-character password must not become an administrator's.
   * A violation is logged with the variable and the codes; nothing is written.
   */
  private boolean environmentPasswordAcceptable(String email) {
    if (!localAuthProperties.hasInitialAdminPassword()) {
      return true;
    }
    List<PasswordPolicy.Violation> violations =
        passwordPolicy.check(localAuthProperties.initialAdminPassword(), email);
    if (violations.isEmpty()) {
      return true;
    }
    log.error(
        "Kein Notanker-Konto der Systemverwaltung angelegt: {} verletzt die Passwortrichtlinie"
            + " ({}). Die Variable anpassen und neu starten - die Anlage wird dann nachgeholt.",
        LocalAuthProperties.INITIAL_ADMIN_PASSWORD_VARIABLE,
        violations.stream().map(PasswordPolicy.Violation::code).collect(Collectors.joining(", ")));
    return false;
  }

  private static boolean looksLikeAnAddress(String email) {
    int at = email.indexOf('@');
    return at > 0 && at < email.length() - 1 && email.indexOf('@', at + 1) < 0;
  }

  private void ensureLocalProviderRow() {
    if (providers.findLocalRow().isEmpty()) {
      providers.save(OidcProvider.localProvider(LOCAL_PROVIDER_DISPLAY_NAME));
    }
  }

  private User createBootstrapUser(String email) {
    User admin = User.localAccount(email, DISPLAY_NAME);
    admin.setOrganizationId(Organization.DEFAULT_ID);
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    // flushed so the pseudonym written by the audit event finds the row
    return users.saveAndFlush(admin);
  }

  /**
   * Sets the password: the environment's as is (no forced change), else a generated one with the
   * change forced at the first sign-in. Returns the generated value - the one thing that must be
   * printed - or {@code null}.
   */
  private String applyPassword(LocalCredentials row, Instant now) {
    if (localAuthProperties.hasInitialAdminPassword()) {
      row.setPasswordHash(passwordEncoder.encode(localAuthProperties.initialAdminPassword()), now);
      row.clearPasswordChangeRequirement(now);
      return null;
    }
    String generated = passwordGenerator.generate();
    row.setPasswordHash(passwordEncoder.encode(generated), now);
    row.requirePasswordChange(PasswordChangeReason.INITIAL, now);
    return generated;
  }

  private static String passwordSource(boolean withPassword, String generated) {
    if (!withPassword) {
      return "NONE";
    }
    return generated == null ? "ENVIRONMENT" : "GENERATED";
  }

  /**
   * The one place a password ever reaches the log (ADR-0033, Entscheidung 5): one WARN event, so it
   * is seen at every usual log level and can be located by {@code Passwort:}. The address is not
   * printed - the operator set it in {@value #INITIAL_ADMIN_EMAIL_VARIABLE}, and no line of OPAA's
   * loggers carries an address (Entscheidung 13).
   */
  private static void printPasswordBlock(String password) {
    String block =
        String.join(
            System.lineSeparator(),
            "",
            RULE,
            "  NOTANKER-KONTO DER SYSTEMVERWALTUNG - EINMALIGE AUSGABE",
            "  Anmeldung:  mit der Adresse aus " + INITIAL_ADMIN_EMAIL_VARIABLE,
            "  Passwort:   " + password,
            "  Der Wechsel des Passworts wird bei der ersten Anmeldung erzwungen.",
            "  Diese Ausgabe erscheint einmalig und wird nicht wiederholt.",
            RULE);
    log.warn("{}", block);
  }

  private void recordAudit(User admin, AuditEventType type, Map<String, Object> after) {
    UUID pseudonym = audit.pseudonymFor(admin.getId(), admin.getOrganizationId());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(admin.getOrganizationId())
            .actorRef(LocalRefreshTokenService.SYSTEM_ACTOR)
            .type(type)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, admin.getId())
            .after(after)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
