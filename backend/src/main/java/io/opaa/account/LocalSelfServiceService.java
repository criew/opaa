package io.opaa.account;

import io.opaa.account.LocalAuthSettings.Values;
import io.opaa.account.LocalSelfServiceAccountService.Registered;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.common.ConflictException;
import io.opaa.common.PublicBaseUrl;
import io.opaa.mail.MailDispatchExecutor;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The self-service of local accounts as the API sees it (ADR-0033, Entscheidungen 9, 10 and 11):
 * the two link flows exist only while the management is switched on, the setting is on (with a
 * domain list for registration) and a public base URL can carry a link; the two link endpoints
 * exist always. Nothing here tells the caller whether an address has an account: after the field
 * checks - which reveal nothing about accounts - "forgot password" and a registration hand their
 * account work (lookup, account, mail) to the {@link MailDispatchExecutor} and answer after {@link
 * #RESPONSE_FLOOR}, so the response time is the same constant in every outcome; synchronous lookups
 * would let the time betray the account. A registration carries no password and only a provisional
 * name: both are set when the verification link is redeemed, so only who holds the mailbox decides
 * them. Deliberately not {@code @Transactional}: the account writes are one transaction each in
 * {@link LocalSelfServiceAccountService} and {@link LocalPasswordService}, run on the executor's
 * thread; the link mail goes to the outbox in that transaction and leaves after its commit.
 */
@Service
public class LocalSelfServiceService implements LocalSelfServiceAvailability {

  /**
   * The least time either of the two address-taking flows takes to answer, whatever the outcome:
   * the account work runs off the request thread, so nothing outcome-dependent shapes the response
   * time - both answer after exactly this.
   */
  public static final Duration RESPONSE_FLOOR = Duration.ofMillis(250);

  private static final Logger log = LoggerFactory.getLogger(LocalSelfServiceService.class);
  static final int DISPLAY_NAME_MAX_LENGTH = 255;

  private final LocalSelfServiceAccountService accounts;
  private final LocalPasswordService passwords;
  private final LocalAuthSettingsRepository settings;
  private final OidcProviderRegistry registry;
  private final PublicBaseUrl publicBaseUrl;
  private final Executor executor;

  @Autowired
  public LocalSelfServiceService(
      LocalSelfServiceAccountService accounts,
      LocalPasswordService passwords,
      LocalAuthSettingsRepository settings,
      OidcProviderRegistry registry,
      PublicBaseUrl publicBaseUrl,
      MailDispatchExecutor dispatch) {
    this(accounts, passwords, settings, registry, publicBaseUrl, (Executor) dispatch);
  }

  LocalSelfServiceService(
      LocalSelfServiceAccountService accounts,
      LocalPasswordService passwords,
      LocalAuthSettingsRepository settings,
      OidcProviderRegistry registry,
      PublicBaseUrl publicBaseUrl,
      Executor executor) {
    this.accounts = accounts;
    this.passwords = passwords;
    this.settings = settings;
    this.registry = registry;
    this.publicBaseUrl = publicBaseUrl;
    this.executor = executor;
  }

  /**
   * What {@code GET /api/v1/auth/config} reports as {@code passwordResetEnabled}. Fails closed: the
   * authorization rule and the rate limiter ask this before any handler runs, and an exception
   * escaping there would answer 500 where an unknown route answers 401 - the disguise would fall
   * exactly while the database is unreachable.
   */
  @Override
  public boolean isPasswordResetAvailable() {
    return unavailableOnFailure(() -> linksPossible() && policyValues().passwordResetEnabled());
  }

  /** What {@code GET /api/v1/auth/config} reports as {@code selfRegistrationEnabled}. */
  @Override
  public boolean isSelfRegistrationAvailable() {
    return unavailableOnFailure(
        () -> {
          Values values = policyValues();
          return linksPossible()
              && values.selfRegistrationEnabled()
              && !values.selfRegistrationAllowedDomains().isEmpty();
        });
  }

  /**
   * A flow whose switches cannot be read is not served; see {@link #isPasswordResetAvailable()}.
   */
  private static boolean unavailableOnFailure(BooleanSupplier switches) {
    try {
      return switches.getAsBoolean();
    } catch (RuntimeException unreadable) {
      log.warn(
          "Could not read the local self-service switches; the flows count as switched off",
          unreadable);
      return false;
    }
  }

  public void setPassword(String rawToken, String newPassword) {
    passwords.setPasswordByLink(rawToken, newPassword);
  }

  public void confirmRegistration(String rawToken, String displayName, String newPassword) {
    passwords.confirmRegistration(rawToken, displayName, newPassword);
  }

  /** Answers after {@link #RESPONSE_FLOOR}; a mail leaves only for an eligible account. */
  public void requestPasswordReset(String email) {
    String address = LocalUserService.requireAddress(email);
    long deadline = System.nanoTime() + RESPONSE_FLOOR.toNanos();
    executor.execute(() -> accounts.issueResetLinkFor(address));
    holdUntil(deadline);
  }

  /**
   * A field error for a bad address comes first - it reveals nothing about accounts. After them,
   * everything runs on the executor and the answer comes after {@link #RESPONSE_FLOOR}: the domain
   * check against the list, the account unless the address is taken (found, or lost in a race on
   * the unique index) or still an unconfirmed self-registration (which gets its link again), and
   * the verification mail.
   */
  public void register(String email) {
    String address = LocalUserService.requireAddress(email);
    long deadline = System.nanoTime() + RESPONSE_FLOOR.toNanos();
    executor.execute(() -> registerOffThread(address));
    holdUntil(deadline);
  }

  private void registerOffThread(String address) {
    if (!isDomainAllowed(address)) {
      return;
    }
    Registered registered;
    try {
      registered = accounts.register(address);
    } catch (ConflictException | DataIntegrityViolationException taken) {
      return;
    }
    log.info(
        "Self-registration issued a verification link for local account {}",
        registered.user().getId());
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
