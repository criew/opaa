package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link InitialAdminPolicy} (ADR-0033, Entscheidung 5): the initial administrator's address grants
 * {@code SYSTEM_ADMIN} through the dev issuer of the {@code dev} mode only - never through an OIDC
 * provider, not even the default one. In the {@code oidc} mode the first administrator is the local
 * bootstrap account the seed creates; provider accounts become administrators by role assignment
 * alone.
 */
class InitialAdminPolicyTest {

  private static final String ADMIN = "admin@opaa.local";
  private static final String DEFAULT_ISSUER = "https://idp.example/realms/beschaeftigte";
  private static final String PARTNER_ISSUER = "https://partner.example/realms/extern";

  private final OidcProviderRepository repository = mock(OidcProviderRepository.class);

  private InitialAdminPolicy policyFor(AuthProperties properties) {
    return new InitialAdminPolicy(properties);
  }

  @Test
  void grantsNothingThroughAnOidcProviderNotEvenTheDefaultOne() {
    OidcProvider standard =
        new OidcProvider(
            "Beschäftigte",
            DEFAULT_ISSUER,
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    standard.markDefault();
    when(repository.findByDefaultProviderTrue()).thenReturn(Optional.of(standard));
    InitialAdminPolicy policy = policyFor(new AuthProperties("oidc", null, null, ADMIN));

    assertThat(policy.grantsSystemAdmin(ADMIN, DEFAULT_ISSUER)).isFalse();
    assertThat(policy.grantsSystemAdmin(ADMIN, DEFAULT_ISSUER + "/")).isFalse();
    assertThat(policy.grantsSystemAdmin(ADMIN, PARTNER_ISSUER)).isFalse();
    assertThat(policy.grantsSystemAdmin(ADMIN, LocalIssuer.URN)).isFalse();
    // the default provider is not even consulted: the rule has no OIDC branch any more
    verify(repository, never()).findByDefaultProviderTrue();
  }

  /**
   * An empty {@code OPAA_INITIAL_ADMIN_EMAIL} in the environment overrides the application default
   * with a blank; in the dev mode that must not take the role from {@code dev-admin}, so a blank
   * address falls back to the default dev user's.
   */
  @Test
  void inTheDevModeABlankAddressFallsBackToTheDefaultDevUsersAddress() {
    AuthProperties dev =
        new AuthProperties(
            "dev",
            null,
            new AuthProperties.DevAuth(
                "opaa-dev",
                "dev-admin",
                java.util.List.of(
                    new AuthProperties.DevUser("dev-admin", ADMIN, "Dev Admin"),
                    new AuthProperties.DevUser("dev-user", "dev-user@opaa.local", "Dev User"))),
            "");
    InitialAdminPolicy policy = policyFor(dev);

    assertThat(policy.grantsSystemAdmin(ADMIN, "opaa-dev")).isTrue();
    assertThat(policy.grantsSystemAdmin("dev-user@opaa.local", "opaa-dev")).isFalse();
    assertThat(policy.grantsSystemAdmin(ADMIN, DEFAULT_ISSUER)).isFalse();
  }

  @Test
  void inTheDevModeTheDevIssuerStillMintsTheDevAdministrator() {
    AuthProperties dev =
        new AuthProperties(
            "dev", null, new AuthProperties.DevAuth("opaa-dev", "dev-admin", null), ADMIN);
    InitialAdminPolicy policy = policyFor(dev);

    assertThat(policy.grantsSystemAdmin(ADMIN, "opaa-dev")).isTrue();
    assertThat(policy.grantsSystemAdmin("Admin@OPAA.local", "opaa-dev")).isTrue();
    assertThat(policy.grantsSystemAdmin("other@opaa.local", "opaa-dev")).isFalse();
    assertThat(policy.grantsSystemAdmin(ADMIN, DEFAULT_ISSUER)).isFalse();
  }

  @Test
  void aBlankAddressWithoutAKnownDefaultDevUserGrantsNothing() {
    // the default user "dev-admin" is not among the configured users: nothing to fall back to
    AuthProperties dev =
        new AuthProperties(
            "dev", null, new AuthProperties.DevAuth("opaa-dev", "dev-admin", null), "  ");
    InitialAdminPolicy policy = policyFor(dev);

    assertThat(policy.grantsSystemAdmin(ADMIN, "opaa-dev")).isFalse();
    assertThat(policy.grantsSystemAdmin(null, "opaa-dev")).isFalse();
  }
}
