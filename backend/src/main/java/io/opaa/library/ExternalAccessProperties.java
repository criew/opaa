package io.opaa.library;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The systemwide bounds of a library's Fremdzugangsfreigabe (#1731,
 * docs/features/external-access.md, "Die Freigabe der Bibliothek").
 *
 * @param maxReleaseDays the longest admissible Befristung. One year by default and deliberately
 *     configurable only downwards in practice - a longer bound would give back the ratchet the
 *     Befristung exists to prevent; {@link LibraryExternalAccessService} rejects a non-positive
 *     value at startup rather than treating it as "no limit".
 * @param reminderLeadDays how many days before the expiry the responsible person is reminded over
 *     the mail mechanics of ADR-0033. A value of {@code 0} switches the reminder off; the release
 *     still expires on its own, it just does so unannounced.
 */
@ConfigurationProperties(prefix = "opaa.external-access")
public record ExternalAccessProperties(int maxReleaseDays, int reminderLeadDays) {

  public ExternalAccessProperties {
    if (maxReleaseDays <= 0) {
      throw new IllegalArgumentException(
          "opaa.external-access.max-release-days must be positive - a release without an upper"
              + " bound is exactly what the Befristung prevents");
    }
    if (reminderLeadDays < 0) {
      throw new IllegalArgumentException(
          "opaa.external-access.reminder-lead-days must not be negative");
    }
  }
}
