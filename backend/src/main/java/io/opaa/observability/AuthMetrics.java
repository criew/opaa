package io.opaa.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Encapsulates all Micrometer metrics related to authentication and user provisioning.
 *
 * <p>A repeatedly failing personal-space provisioning (see {@code PersonalSpaceProvisioner}) must
 * be genuinely visible, not merely logged with a hand-rolled counter that resets on every restart
 * and lives outside every dashboard/alerting rule the existing {@link IndexingMetrics}/{@link
 * QueryMetrics} counters already feed - this is that same, already standing Micrometer
 * infrastructure, not new infrastructure of its own.
 */
public class AuthMetrics {

  public static final String ACCOUNT_LOCKOUT_METRIC = "opaa.auth.local_account_lockout";

  /**
   * Refused sign-ins by {@code method} and {@code result}, without account, address or provider.
   * Successful ones are not counted for any method: over time they would draw the working hours of
   * the staff (ADR-0071, Entscheidung 10).
   */
  public static final String LOGINS_METRIC = "opaa.auth.logins";

  /** How a person signs in. */
  public enum LoginMethod {
    LOCAL(LoginResult.BAD_CREDENTIALS, LoginResult.LOCKED, LoginResult.OTHER),
    /** The sign-in itself happens at the provider; OPAA sees only the tokens it refuses. */
    OIDC(LoginResult.BAD_CREDENTIALS, LoginResult.IDP_ERROR, LoginResult.OTHER);

    private final List<LoginResult> results;

    LoginMethod(LoginResult... results) {
      this.results = List.of(results);
    }
  }

  /**
   * Why a sign-in was refused: {@code IDP_ERROR} only when the signing keys of the identity
   * provider could not be fetched to check a token.
   */
  public enum LoginResult {
    BAD_CREDENTIALS,
    LOCKED,
    IDP_ERROR,
    OTHER
  }

  private final Counter personalSpaceProvisioningFailedCounter;
  private final Counter accountLockoutCounter;
  private final Map<LoginMethod, Map<LoginResult, Counter>> logins =
      new EnumMap<>(LoginMethod.class);

  public AuthMetrics(MeterRegistry meterRegistry) {
    this.personalSpaceProvisioningFailedCounter =
        Counter.builder("opaa.auth.personal_space_provisioning")
            .tag("result", "failed")
            .description("Failed personal space provisioning attempts")
            .register(meterRegistry);
    this.accountLockoutCounter =
        Counter.builder(ACCOUNT_LOCKOUT_METRIC)
            .description("Local accounts locked after too many failed sign-ins (ADR-0033)")
            .register(meterRegistry);
    for (LoginMethod method : LoginMethod.values()) {
      Map<LoginResult, Counter> byResult = new EnumMap<>(LoginResult.class);
      for (LoginResult result : method.results) {
        byResult.put(
            result,
            Counter.builder(LOGINS_METRIC)
                .tag("method", method.name().toLowerCase(Locale.ROOT))
                .tag("result", result.name().toLowerCase(Locale.ROOT))
                .description("Refused sign-ins by method and reason, installation-wide")
                .register(meterRegistry));
      }
      logins.put(method, byResult);
    }
  }

  /** Counts one sign-in of {@code method} refused with {@code result}. */
  public void recordLogin(LoginMethod method, LoginResult result) {
    Counter counter = logins.get(method).get(result);
    if (counter == null) {
      throw new IllegalArgumentException(method + " sign-ins never end with " + result);
    }
    counter.increment();
  }

  public void recordPersonalSpaceProvisioningFailed() {
    personalSpaceProvisioningFailedCounter.increment();
  }

  /** Total failed attempts since startup - logged alongside every failure, see the call site. */
  public double personalSpaceProvisioningFailedCount() {
    return personalSpaceProvisioningFailedCounter.count();
  }

  public void recordAccountLockedAfterFailedLogins() {
    accountLockoutCounter.increment();
  }
}
