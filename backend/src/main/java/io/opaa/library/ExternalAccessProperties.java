package io.opaa.library;

import io.opaa.compliance.DataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The systemwide bounds of a library's Fremdzugangsfreigabe (docs/features/external-access.md, "Die
 * Freigabe der Bibliothek") and of the access tokens of accounts of an identity provider.
 *
 * @param maxReleaseDays the longest admissible Befristung. One year by default and deliberately
 *     configurable only downwards in practice - a longer bound would give back the ratchet the
 *     Befristung exists to prevent. The compact constructor below refuses a non-positive value at
 *     startup rather than treating it as "no limit". It also bounds the reminder lead ({@link
 *     ExternalAccessReminderLead}).
 * @param providerSignInMaxAgeDays how long the last sign-in of an account of an identity provider
 *     may lie back for its access tokens to be accepted. OPAA learns of a deactivation at the
 *     provider and of changed groups from the groups claim only at a sign-in; a token that outlives
 *     them by more than this is refused until the person signs in again. Must be positive.
 */
@ConfigurationProperties(prefix = "opaa.external-access")
public record ExternalAccessProperties(
    @DataImpact int maxReleaseDays, @DataImpact int providerSignInMaxAgeDays) {

  public ExternalAccessProperties {
    if (maxReleaseDays <= 0) {
      throw new IllegalArgumentException(
          "opaa.external-access.max-release-days must be positive - a release without an upper"
              + " bound is exactly what the Befristung prevents");
    }
    if (providerSignInMaxAgeDays <= 0) {
      throw new IllegalArgumentException(
          "opaa.external-access.provider-sign-in-max-age-days must be positive - without a bound a"
              + " token outlives a deactivation at the identity provider");
    }
  }
}
