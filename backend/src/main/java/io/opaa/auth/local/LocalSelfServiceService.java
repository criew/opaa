package io.opaa.auth.local;

import io.opaa.auth.local.LocalAuthSettings.Values;
import io.opaa.auth.local.LocalSelfServiceAccountService.Registered;
import io.opaa.auth.oidc.OidcProviderRegistry;
import io.opaa.common.ConflictException;
import io.opaa.common.PublicBaseUrl;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
 * exist always. Nothing here tells the caller whether an address has an account: "forgot password"
 * and a registration answer after the same {@link #RESPONSE_FLOOR} whether or not a mail leaves, a
 * registration pays the hashing cost before it looks at the address, a taken address is silently no
 * account, and mail leaves on a thread of its own - synchronous SMTP would let the response time
 * betray the account. Deliberately not {@code @Transactional}: the account writes are one
 * transaction each in {@link LocalSelfServiceAccountService} and {@link LocalPasswordService}, and
 * the send follows their commit.
 */
@Service
public class LocalSelfServiceService {

  /**
   * The least time either of the two address-taking flows takes to answer - well above the database
   * work of the account-exists path, so the difference between "account" and "no account"
   * disappears in it.
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
      PasswordPolicy policy) {
    this(
        accounts,
        passwords,
        mailer,
        settings,
        registry,
        publicBaseUrl,
        passwordEncoder,
        policy,
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "local-self-service-mailer");
              thread.setDaemon(true);
              return thread;
            }));
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

  /** Always returns after {@link #RESPONSE_FLOOR}; a mail leaves only for an eligible account. */
  public void requestPasswordReset(String email) {
    String address = LocalUserService.requireAddress(email);
    long deadline = System.nanoTime() + RESPONSE_FLOOR.toNanos();
    try {
      accounts
          .issueResetLinkFor(address)
          .ifPresent(
              link -> executor.execute(() -> mailer.sendPasswordReset(link.user(), link.token())));
    } finally {
      holdUntil(deadline);
    }
  }

  /**
   * Field errors for a bad address, name or password come first - they reveal nothing about
   * accounts. Then the hash is computed whatever follows, the domain checked against the list, and
   * the account created unless the address is taken (found, or lost in a race on the unique index);
   * only a created account gets the verification mail. Every path answers after the floor.
   */
  public void register(String email, String displayName, String password) {
    String address = LocalUserService.requireAddress(email);
    String name = LocalUserService.requireText("displayName", displayName, DISPLAY_NAME_MAX_LENGTH);
    policy.require("password", password, address);
    long deadline = System.nanoTime() + RESPONSE_FLOOR.toNanos();
    try {
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
      log.info("Self-registration created local account {}", registered.user().getId());
      executor.execute(
          () -> mailer.sendRegistrationVerification(registered.user(), registered.verification()));
    } finally {
      holdUntil(deadline);
    }
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
