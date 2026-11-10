package io.opaa.library;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The systemwide bounds of a library's Fremdzugangsfreigabe (ADR-0035) and of the access tokens of
 * accounts of an identity provider.
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
 * @param maxActiveTokensPerAccount how many personal access tokens one account may hold at once
 *     (neither revoked nor expired); an issuance beyond it is refused. Every token brings its own
 *     hour quota, so without a bound one account could multiply its share of the channel. Must be
 *     positive.
 */
@ConfigurationProperties(prefix = "opaa.external-access")
public record ExternalAccessProperties(
    @DataImpact int maxReleaseDays,
    @DataImpact int providerSignInMaxAgeDays,
    @NoDataImpact("a load bound") @InstallationWide int maxActiveTokensPerAccount) {

  /** The shipped bound of active tokens per account, as in {@code application.yml}. */
  public static final int DEFAULT_MAX_ACTIVE_TOKENS_PER_ACCOUNT = 10;

  /** With the shipped bound of active tokens per account. */
  public ExternalAccessProperties(int maxReleaseDays, int providerSignInMaxAgeDays) {
    this(maxReleaseDays, providerSignInMaxAgeDays, DEFAULT_MAX_ACTIVE_TOKENS_PER_ACCOUNT);
  }

  @ConstructorBinding
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
    if (maxActiveTokensPerAccount <= 0) {
      throw new IllegalArgumentException(
          "opaa.external-access.max-active-tokens-per-account must be positive - without a bound"
              + " every further token multiplies an account's quota");
    }
  }
}
