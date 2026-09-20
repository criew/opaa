package io.opaa.group.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for #237's directory synchronisation.
 *
 * @param changeThresholdFraction the plausibility threshold: if a run would remove more than this
 *     fraction of a group's existing memberships, nothing is written and the plan is left for a
 *     system administrator to confirm or discard (#1816, ADR-0036 Entscheidung 3). Deliberately
 *     measured on removals only, not on additions - a run that only adds memberships can never
 *     revoke a right, so there is nothing for the threshold to protect against there. Must be
 *     strictly between 0 (exclusive) and 1 (inclusive); the application default is 0.3 (30%), set
 *     in {@code application.yml} - not here, so that an operator-supplied value of 0 or less is
 *     rejected rather than silently replaced with a more lenient default. A safeguard against mass
 *     rights revocation must fail loudly on invalid configuration, not quietly loosen itself.
 * @param scheduleEnabled whether {@link DirectorySyncScheduler}'s tick exists at all. On in
 *     production; the backend test suite switches it off so a run never starts behind a test's
 *     back, and exercises the tick by calling it.
 */
@ConfigurationProperties(prefix = "opaa.directory-sync")
public record DirectorySyncProperties(double changeThresholdFraction, boolean scheduleEnabled) {

  public DirectorySyncProperties {
    if (changeThresholdFraction <= 0 || changeThresholdFraction > 1) {
      throw new IllegalArgumentException(
          "opaa.directory-sync.change-threshold-fraction must be greater than 0 and at most 1,"
              + " got "
              + changeThresholdFraction);
    }
  }
}
