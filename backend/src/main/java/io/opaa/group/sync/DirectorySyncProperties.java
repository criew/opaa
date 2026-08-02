package io.opaa.group.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for #237's directory synchronisation.
 *
 * @param changeThresholdFraction the plausibility threshold: if a run would remove more than this
 *     fraction of a group's existing memberships, it is aborted and reported instead of applied
 *     (see #237's acceptance criteria). Deliberately measured on removals only, not on additions -
 *     a run that only adds memberships can never revoke a right, so there is nothing for the
 *     threshold to protect against there. Must be strictly between 0 and 1; defaults to 0.3 (30%).
 */
@ConfigurationProperties(prefix = "opaa.directory-sync")
public record DirectorySyncProperties(double changeThresholdFraction) {

  public DirectorySyncProperties {
    if (changeThresholdFraction <= 0) {
      changeThresholdFraction = 0.3;
    }
    if (changeThresholdFraction > 1) {
      throw new IllegalArgumentException(
          "opaa.directory-sync.change-threshold-fraction must be at most 1, got "
              + changeThresholdFraction);
    }
  }
}
