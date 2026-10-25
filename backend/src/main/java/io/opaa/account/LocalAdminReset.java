package io.opaa.account;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.PasswordChangeReason;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.OidcProvidersChangedEvent;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.TransactionCallbacks;
import io.opaa.security.PasswordGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code OPAA_LOCAL_ADMIN_RESET=force} (ADR-0033, Entscheidung 5): restores the emergency account
 * of the installation once at start-up in the {@code oidc} mode - unlocked, expiry cleared, {@code
 * SYSTEM_ADMIN} restored, a generated password printed once with the change forced at the next
 * sign-in, every session ended. The account itself only ever comes from the installation (ADR-0060,
 * Entscheidung 8); without one there is nothing to restore. Takes effect once per setting of the
 * variable: the reset is kept in {@link LocalAdminResetRecord}, and a start without the variable
 * clears it. Instances run it one after another under the start-up lock, and a password reaches the
 * log only once its transaction committed.
 */
@Component
public class LocalAdminReset {

  private static final Logger log = LoggerFactory.getLogger(LocalAdminReset.class);
  private static final String OIDC_MODE = "oidc";
  private static final String RULE =
      "=====================================================================";

  /** What a start did - for the log and the tests. */
  public enum Outcome {
    /** Not the {@code oidc} mode, or the variable is not set. */
    SKIPPED,
    /** The variable is set, but there is no emergency account to restore. */
    REJECTED,
    /** The account was restored. */
    RESET,
    /** The variable is set, but this or another instance already reset since it was set. */
    RESET_ALREADY_DONE
  }

  private final AuthProperties authProperties;
  private final LocalAuthProperties localAuthProperties;
  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final LocalRefreshTokenRepository refreshTokens;
  private final OidcProviderRepository providers;
  private final LocalAdminResetRecordRepository record;
  private final PasswordEncoder passwordEncoder;
  private final PasswordGenerator passwordGenerator;
  private final AuditEventRecorder audit;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public LocalAdminReset(
      AuthProperties authProperties,
      LocalAuthProperties localAuthProperties,
      UserRepository users,
      LocalCredentialsRepository credentials,
      LocalRefreshTokenRepository refreshTokens,
      OidcProviderRepository providers,
      LocalAdminResetRecordRepository record,
      PasswordEncoder passwordEncoder,
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
    this.record = record;
    this.passwordEncoder = passwordEncoder;
    this.passwordGenerator = passwordGenerator;
    this.audit = audit;
    this.events = events;
    this.clock = clock;
  }

  /** The start: resets when the variable asks for it, otherwise frees the next reset. */
  @Transactional
  public Outcome resetIfForced() {
    if (!OIDC_MODE.equals(authProperties.mode())) {
      return Outcome.SKIPPED;
    }
    if (!localAuthProperties.isAdminResetForced()) {
      releaseNextReset();
      return Outcome.SKIPPED;
    }
    return reset();
  }

  /**
   * A start with the variable: restores the account unless a reset since it was set is recorded.
   */
  @Transactional
  public Outcome reset() {
    record.lockStartupSeeds();
    if (findRecord().filter(LocalAdminResetRecord::forcedResetDone).isPresent()) {
      log.info(
          "{}=force: Das Notanker-Konto wurde seit dem Setzen der Variable bereits zurückgesetzt"
              + " (von dieser oder einer anderen Instanz) - kein weiterer Reset. Die Variable jetzt"
              + " entfernen; erst ein Start ohne sie gibt einen neuen Reset frei.",
          LocalAuthProperties.ADMIN_RESET_VARIABLE);
      return Outcome.RESET_ALREADY_DONE;
    }
    Optional<LocalCredentials> existing = credentials.findByBootstrapTrue();
    if (existing.isEmpty()) {
      log.error(
          "{}=force: Es gibt kein Notanker-Konto der Systemverwaltung, das sich zurücksetzen"
              + " ließe; es entsteht nur mit der Installation.",
          LocalAuthProperties.ADMIN_RESET_VARIABLE);
      return Outcome.REJECTED;
    }
    Instant now = clock.instant();
    LocalCredentials row = existing.get();
    User admin = users.findById(row.getUserId()).orElseThrow();
    row.unlock(now);
    row.setExpiresAt(null, now);
    if (row.getEmailVerifiedAt() == null) {
      row.markEmailVerified(now);
    }
    if (admin.getSystemRole() != SystemRole.SYSTEM_ADMIN) {
      admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
      users.save(admin);
    }
    if (providers.findLocalRow().isEmpty()) {
      providers.save(OidcProvider.localProvider(InitialAdministrator.LOCAL_PROVIDER_DISPLAY_NAME));
    }
    String generated = passwordGenerator.generate();
    row.setPasswordHash(passwordEncoder.encode(generated), now);
    row.requirePasswordChange(PasswordChangeReason.INITIAL, now);
    row.invalidateSessionsIssuedBefore(LocalTokenRevocationService.cutoffFor(now), now);
    credentials.save(row);
    refreshTokens.revokeAllForUser(admin.getId(), RevocationReason.ADMIN_RESET, now);
    LocalAdminResetRecord done = findRecord().orElseGet(() -> new LocalAdminResetRecord(now));
    done.markForcedReset(now);
    record.save(done);
    recordAudit(admin);
    events.publishEvent(new OidcProvidersChangedEvent());
    TransactionCallbacks.afterCommitOrNow(() -> printPasswordBlock(generated));
    log.warn(
        "{}=force: Notanker-Konto der Systemverwaltung wiederhergestellt (Konto {}), alle seine"
            + " Sitzungen beendet. Die Variable jetzt wieder entfernen - weitere Starts mit ihr"
            + " setzen nichts zurück, erst ein Start ohne sie gibt einen neuen Reset frei.",
        LocalAuthProperties.ADMIN_RESET_VARIABLE,
        admin.getId());
    return Outcome.RESET;
  }

  private Optional<LocalAdminResetRecord> findRecord() {
    return record.findById(LocalAdminResetRecord.SINGLETON_ID);
  }

  /** A start without the variable gives the next {@code OPAA_LOCAL_ADMIN_RESET=force} free. */
  @Transactional
  public void releaseNextReset() {
    record.lockStartupSeeds();
    findRecord()
        .filter(LocalAdminResetRecord::forcedResetDone)
        .ifPresent(
            seen -> {
              seen.clearForcedReset();
              record.save(seen);
            });
  }

  /**
   * The one place a password ever reaches the log (ADR-0033, Entscheidung 5): one WARN event, so it
   * is seen at every usual log level and can be located by {@code Passwort:}. The address is not
   * printed; no line of OPAA's loggers carries one (Entscheidung 13).
   */
  private static void printPasswordBlock(String password) {
    String block =
        String.join(
            System.lineSeparator(),
            "",
            RULE,
            "  NOTANKER-KONTO DER SYSTEMVERWALTUNG - EINMALIGE AUSGABE",
            "  Anmeldung:  mit der Adresse des ersten Kontos der Installation",
            "  Passwort:   " + password,
            "  Der Wechsel des Passworts wird bei der ersten Anmeldung erzwungen.",
            "  Diese Ausgabe erscheint einmalig und wird nicht wiederholt.",
            RULE);
    log.warn("{}", block);
  }

  private void recordAudit(User admin) {
    UUID pseudonym = audit.pseudonymFor(admin.getId(), admin.getOrganizationId());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(admin.getOrganizationId())
            .actorRef(LocalRefreshTokenService.SYSTEM_ACTOR)
            .type(AuditEventType.LOCAL_ADMIN_RESET)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, admin.getId())
            .after(Map.of("recreated", false, "passwordSource", "GENERATED"))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
