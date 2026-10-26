package io.opaa.library;

import io.opaa.compliance.DataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The systemwide bounds of a library's Fremdzugangsfreigabe (docs/features/external-access.md, "Die
 * Freigabe der Bibliothek").
 *
 * @param maxReleaseDays the longest admissible Befristung. One year by default and deliberately
 *     configurable only downwards in practice - a longer bound would give back the ratchet the
 *     Befristung exists to prevent. The compact constructor below refuses a non-positive value at
 *     startup rather than treating it as "no limit". It also bounds the reminder lead ({@link
 *     ExternalAccessReminderLead}).
 */
@ConfigurationProperties(prefix = "opaa.external-access")
public record ExternalAccessProperties(@DataImpact int maxReleaseDays) {

  public ExternalAccessProperties {
    if (maxReleaseDays <= 0) {
      throw new IllegalArgumentException(
          "opaa.external-access.max-release-days must be positive - a release without an upper"
              + " bound is exactly what the Befristung prevents");
    }
  }
}
