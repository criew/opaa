package io.opaa.auth.local;

import io.opaa.auth.local.LocalAuthSettings.Values;
import io.opaa.auth.local.LocalSelfServiceAccountService.Registered;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.common.ConflictException;
import io.opaa.common.PublicBaseUrl;
import io.opaa.mail.MailDispatchExecutor;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * The self-service of local accounts as the API sees it (ADR-0033, Entscheidungen 9, 10 and 11):
 * the two link flows exist only while the management is switched on, the setting is on (with a
 * domain list for registration) and a public base URL can carry a link; the two link endpoints
 * exist always. Nothing here tells the caller whether an address has an account: after the field
 * checks - which reveal nothing about accounts - "forgot password" and a registration hand their
 * whole work (hash, lookup, account, mail) to the {@link MailDispatchExecutor} and answer after
 * exactly {@link #RESPONSE_FLOOR}, so the response time is the same constant in every outcome;
 * synchronous hashing or SMTP would let it betray the account. Deliberately not
 * {@code @Transactional}: the account writes are one transaction each in {@link
 * LocalSelfServiceAccountService} and {@link LocalPasswordService}, run on the executor's thread,
 * and the send follows their commit.
 */
@Service
public class LocalSelfServiceService {

  /**
   * The time either of the two address-taking flows takes to answer, whatever the outcome: the work
   * itself runs off the request thread, so nothing but this constant shapes the response time.
   */
  public static final Duration RESPONSE_FLOOR = Duration.ofMillis(250);

  private static final Logger log = LoggerFactory.getLogger(LocalSelfServiceService.class);
  private static final int DISPLAY_NAME_MAX_LENGTH = 255;

  private final LocalSelfServiceAccountService accounts;
  private final LocalPasswordService passwords;
  private final LocalAccountMailer mailer;
  private final LocalAuthSettingsRepository settings;
  private final OidcProviderRegistry registry;
  private final PublicBaseUrl publicBaseUrl;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy policy;
  private final Executor executor;

  @Autowired
  public LocalSelfServiceService(
      LocalSelfServiceAccountService accounts,
      LocalPasswordService passwords,
      LocalAccountMailer mailer,
      LocalAuthSettingsRepository settings,
      OidcProviderRegistry registry,
      PublicBaseUrl publicBaseUrl,
      PasswordEncoder passwordEncoder,
      PasswordPolicy policy,
      MailDispatchExecutor dispatch) {
    this(
        accounts,
        passwords,
        mailer,
        settings,
        registry,
        publicBaseUrl,
        passwordEncoder,
        policy,
        (Executor) dispatch);
  }

  LocalSelfServiceService(
      LocalSelfServiceAccountService accounts,
      LocalPasswordService passwords,
      LocalAccountMailer mailer,
      LocalAuthSettingsRepository settings,
      OidcProviderRegistry registry,
      PublicBaseUrl publicBaseUrl,
      PasswordEncoder passwordEncoder,
      PasswordPolicy policy,
      Executor executor) {
    this.accounts = accounts;
    this.passwords = passwords;
    this.mailer = mailer;
    this.settings = settings;
    this.registry = registry;
    this.publicBaseUrl = publicBaseUrl;
    this.passwordEncoder = passwordEncoder;
    this.policy = policy;
    this.executor = executor;
  }

  /** What {@code GET /api/v1/auth/config} reports as {@code passwordResetEnabled}. */
  public boolean isPasswordResetAvailable() {
    return linksPossible() && policyValues().passwordResetEnabled();
  }

  /** What {@code GET /api/v1/auth/config} reports as {@code selfRegistrationEnabled}. */
  public boolean isSelfRegistrationAvailable() {
    Values values = policyValues();
    return linksPossible()
        && values.selfRegistrationEnabled()
        && !values.selfRegistrationAllowedDomains().isEmpty();
  }

  public void setPassword(String rawToken, String newPassword) {
    passwords.setPasswordByLink(rawToken, newPassword);
  }

  public void verifyEmail(String rawToken) {
    accounts.verifyEmail(rawToken);
  }

  /** Answers after {@link #RESPONSE_FLOOR}; a mail leaves only for an eligible account. */
  public void requestPasswordReset(String email) {
    String address = LocalUserService.requireAddress(email);
    long deadline = System.nanoTime() + RESPONSE_FLOOR.toNanos();
    executor.execute(
        () ->
            accounts
                .issueResetLinkFor(address)
                .ifPresent(link -> mailer.sendPasswordReset(link.user(), link.token())));
    holdUntil(deadline);
  }

  /**
   * Field errors for a bad address, name or password come first - they reveal nothing about
   * accounts. Everything after them runs on the executor: the hash, the domain check against the
   * list, the account unless the address is taken (found, or lost in a race on the unique index) or
   * still an unconfirmed self-registration (which gets its link again), and the verification mail.
   */
  public void register(String email, String displayName, String password) {
    String address = LocalUserService.requireAddress(email);
    String name = LocalUserService.requireText("displayName", displayName, DISPLAY_NAME_MAX_LENGTH);
    policy.require("password", password, address);
    long deadline = System.nanoTime() + RESPONSE_FLOOR.toNanos();
    executor.execute(() -> registerOffThread(address, name, password));
    holdUntil(deadline);
  }

  private void registerOffThread(String address, String name, String password) {
    String hash = passwordEncoder.encode(password);
    if (!isDomainAllowed(address)) {
      return;
    }
    Registered registered;
    try {
      registered = accounts.register(address, name, hash);
    } catch (ConflictException | DataIntegrityViolationException taken) {
      return;
    }
    log.info(
        "Self-registration issued a verification link for local account {}",
        registered.user().getId());
    mailer.sendRegistrationVerification(registered.user(), registered.verification());
  }

  private boolean linksPossible() {
    return registry.localAccountsEnabled() && publicBaseUrl.isConfigured();
  }

  private boolean isDomainAllowed(String address) {
    String domain = address.substring(address.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
    return policyValues().selfRegistrationAllowedDomains().contains(domain);
  }

  private Values policyValues() {
    return settings.findSingleton().map(LocalAuthSettings::values).orElseGet(Values::defaults);
  }

  private static void holdUntil(long deadlineNanos) {
    long remaining = deadlineNanos - System.nanoTime();
    while (remaining > 0) {
      try {
        Thread.sleep(Duration.ofNanos(remaining));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
      remaining = deadlineNanos - System.nanoTime();
    }
  }
}
