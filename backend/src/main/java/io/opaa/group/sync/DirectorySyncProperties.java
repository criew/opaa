package io.opaa.group.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for #237's directory synchronisation.
 *
 * @param changeThresholdFraction the plausibility threshold: if a run would remove more than this
 *     fraction of a group's existing memberships, it is aborted and reported instead of applied
 *     (see #237's acceptance criteria). Deliberately measured on removals only, not on additions -
 *     a run that only adds memberships can never revoke a right, so there is nothing for the
 *     threshold to protect against there. Must be strictly between 0 (exclusive) and 1 (inclusive);
 *     the application default is 0.3 (30%), set in {@code application.yml} - not here, so that an
 *     operator-supplied value of 0 or less is rejected rather than silently replaced with a more
 *     lenient default. A safeguard against mass rights revocation must fail loudly on invalid
 *     configuration, not quietly loosen itself.
 */
@ConfigurationProperties(prefix = "opaa.directory-sync")
public record DirectorySyncProperties(double changeThresholdFraction) {

  public DirectorySyncProperties {
    if (changeThresholdFraction <= 0 || changeThresholdFraction > 1) {
      throw new IllegalArgumentException(
          "opaa.directory-sync.change-threshold-fraction must be greater than 0 and at most 1,"
              + " got "
              + changeThresholdFraction);
    }
  }
}
