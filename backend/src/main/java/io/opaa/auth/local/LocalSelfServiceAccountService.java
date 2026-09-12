package io.opaa.auth.local;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.LocalAccountState;
import io.opaa.api.types.LockReason;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.LocalIssuer;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalActionTokenService.IssuedActionToken;
import io.opaa.common.ConflictException;
import io.opaa.organization.Organization;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account writes of the self-service (ADR-0033, Entscheidungen 3, 11 and 13), one transaction
 * each: the registration that creates a {@code users} row of the local issuer together with its
 * {@code local_credentials} row (the invariant {@link LocalUserService} keeps for the
 * administration), the confirmation of the address, and the issue of a self-requested reset link.
 * No mail here - {@link LocalSelfServiceService} sends after the commit. Registration is audited
 * under the system process {@code local-auth} with the account as a pseudonym and never with the
 * address or the name.
 */
@Service
public class LocalSelfServiceAccountService {

  public static final Duration VERIFY_EMAIL_TTL = Duration.ofHours(24);
  public static final String CREATED_REASON = "Selbstregistrierung";

  /** A registered account with the one secret to mail: the verification link. */
  public record Registered(
      User user, LocalCredentials credentials, IssuedActionToken verification) {
    @Override
    public String toString() {
      return "Registered[user=" + user.getId() + "]";
    }
  }

  /** A self-requested reset link of an eligible account. */
  public record ResetLink(User user, IssuedActionToken token) {
    @Override
    public String toString() {
      return "ResetLink[user=" + user.getId() + "]";
    }
  }

  private final UserRepository users;
  private final LocalCredentialsRepository credentials;
  private final LocalAuthSettingsRepository settings;
  private final LocalActionTokenService actionTokens;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public LocalSelfServiceAccountService(
      UserRepository users,
      LocalCredentialsRepository credentials,
      LocalAuthSettingsRepository settings,
      LocalActionTokenService actionTokens,
      AuditEventRecorder audit,
      Clock clock) {
    this.users = users;
    this.credentials = credentials;
    this.settings = settings;
    this.actionTokens = actionTokens;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Creates the account for an already validated address and name with an already encoded hash:
   * role {@code USER}, expiry {@code default_expiry_days} from now (mandatory), reason {@link
   * #CREATED_REASON}, address unconfirmed. A taken address is a {@link ConflictException} ({@code
   * EMAIL_TAKEN}); an insert lost to a concurrent registration surfaces as the unique index's
   * {@code DataIntegrityViolationException} - the caller treats both as "no account", silently. The
   * one exception is a still unconfirmed self-registration of the same address: it gets a fresh
   * verification link (the older one is voided) and nothing else - neither the hash nor the name
   * are replaced, or a second registration could plant a password the first person then confirms.
   * The personal space is not provisioned here: the first sign-in after the confirmation creates it
   * on the path every sign-in takes, so an unconfirmed registration leaves no space behind.
   */
  @Transactional
  public Registered register(String email, String displayName, String passwordHash) {
    Instant now = clock.instant();
    Optional<User> existing = users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email);
    if (existing.isPresent()) {
      return pendingRegistration(existing.get(), now).orElseThrow(LocalUserService::addressTaken);
    }
    User user = User.localAccount(email, displayName);
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setLastLoginAt(null);
    user = users.saveAndFlush(user);
    LocalCredentials row = new LocalCredentials(user.getId(), CREATED_REASON, now);
    row.setPasswordHash(passwordHash, now);
    Instant expiresAt = now.plus(Duration.ofDays(policy().defaultExpiryDays()));
    row.setExpiresAt(expiresAt, now);
    row = credentials.save(row);
    IssuedActionToken verification =
        actionTokens.issue(user.getId(), ActionTokenPurpose.VERIFY_EMAIL, VERIFY_EMAIL_TTL);
    UUID pseudonym = audit.pseudonymFor(user.getId(), user.getOrganizationId());
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actorRef(LocalRefreshTokenService.SYSTEM_ACTOR)
            .type(AuditEventType.LOCAL_USER_REGISTERED)
            .object(AuditObjectType.USER_ACCOUNT, pseudonym, null)
            .subject(AuditSubjectKind.USER, user.getId())
            .after(Map.of("expiresAt", expiresAt.toString()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
    return new Registered(user, row, verification);
  }

  /** The account again with a fresh verification link, if it is a self-registration still open. */
  private Optional<Registered> pendingRegistration(User user, Instant now) {
    return credentials
        .findById(user.getId())
        .filter(
            row ->
                row.getEmailVerifiedAt() == null
                    && row.getPasswordHash() != null
                    && CREATED_REASON.equals(row.getCreatedReason())
                    && LocalAccountAccess.mayRedeemLink(row, now))
        .map(
            row ->
                new Registered(
                    user,
                    row,
                    actionTokens.issue(
                        user.getId(), ActionTokenPurpose.VERIFY_EMAIL, VERIFY_EMAIL_TTL)));
  }

  /**
   * Redeems a {@code VERIFY_EMAIL} link exactly once and confirms the address. Every refused token
   * is the same {@link LocalActionTokenService#invalidToken}, including one of an account that may
   * no longer redeem a link ({@link LocalAccountAccess#mayRedeemLink}).
   */
  @Transactional
  public User verifyEmail(String rawToken) {
    Instant now = clock.instant();
    LocalActionToken token =
        actionTokens
            .redeem(rawToken, ActionTokenPurpose.VERIFY_EMAIL)
            .orElseThrow(LocalActionTokenService::invalidToken);
    User user =
        users
            .findById(token.getUserId())
            .filter(found -> LocalIssuer.URN.equals(found.getIssuer()))
            .orElseThrow(LocalActionTokenService::invalidToken);
    LocalCredentials row =
        credentials
            .findById(user.getId())
            .filter(found -> LocalAccountAccess.mayRedeemLink(found, now))
            .orElseThrow(LocalActionTokenService::invalidToken);
    if (row.getEmailVerifiedAt() == null) {
      row.markEmailVerified(now);
      credentials.save(row);
    }
    return user;
  }

  /**
   * A fresh {@code RESET_PASSWORD} link ({@code reset_token_ttl_minutes}; it voids older ones) for
   * the local account under {@code email} - only for an {@code ACTIVE} account or one in a
   * failed-login lockout (ADR-0033, Entscheidung 9); every other state and an unknown address yield
   * empty. Nothing else changes: sessions end when the link is redeemed, not when it is asked for.
   */
  @Transactional
  public Optional<ResetLink> issueResetLinkFor(String email) {
    Instant now = clock.instant();
    Optional<User> user = users.findByIssuerAndEmailIgnoreCase(LocalIssuer.URN, email.trim());
    if (user.isEmpty()) {
      return Optional.empty();
    }
    Optional<LocalCredentials> row = credentials.findById(user.get().getId());
    if (row.isEmpty() || !isEligibleForReset(row.get(), now)) {
      return Optional.empty();
    }
    IssuedActionToken token =
        actionTokens.issue(
            user.get().getId(),
            ActionTokenPurpose.RESET_PASSWORD,
            Duration.ofMinutes(policy().resetTokenTtlMinutes()));
    return Optional.of(new ResetLink(user.get(), token));
  }

  private static boolean isEligibleForReset(LocalCredentials row, Instant now) {
    LocalAccountState state = row.state(now);
    return state == LocalAccountState.ACTIVE
        || (state == LocalAccountState.LOCKED && row.getLockedReason() == LockReason.FAILED_LOGINS);
  }

  private LocalAuthSettings.Values policy() {
    return settings
        .findSingleton()
        .map(LocalAuthSettings::values)
        .orElseGet(LocalAuthSettings.Values::defaults);
  }
}
