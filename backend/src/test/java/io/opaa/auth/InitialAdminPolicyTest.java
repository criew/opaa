package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.auth.oidc.OidcProviderRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link InitialAdminPolicy} (#1330, ADR-0025 Entscheidung 3): the initial administrator's address
 * grants {@code SYSTEM_ADMIN} only through the default provider ({@code oidc}) or the dev issuer
 * ({@code dev}) - never through a second provider, whose operator could otherwise mint one.
 */
class InitialAdminPolicyTest {

  private static final String ADMIN = "admin@opaa.local";
  private static final String DEFAULT_ISSUER = "https://idp.example/realms/beschaeftigte";
  private static final String PARTNER_ISSUER = "https://partner.example/realms/extern";

  private final OidcProviderRepository repository = mock(OidcProviderRepository.class);

  private InitialAdminPolicy policyFor(AuthProperties properties) {
    return new InitialAdminPolicy(properties, new TrustedProvider(properties, repository));
  }

  private InitialAdminPolicy oidcPolicy() {
    OidcProvider standard =
        new OidcProvider(
            "Beschäftigte",
            DEFAULT_ISSUER,
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    standard.markDefault();
    when(repository.findByDefaultProviderTrue()).thenReturn(Optional.of(standard));
    return policyFor(new AuthProperties("oidc", null, null, ADMIN));
  }

  @Test
  void grantsThroughTheDefaultProviderOnly() {
    InitialAdminPolicy policy = oidcPolicy();

    assertThat(policy.grantsSystemAdmin(ADMIN, DEFAULT_ISSUER)).isTrue();
    assertThat(policy.grantsSystemAdmin("Admin@OPAA.local", DEFAULT_ISSUER + "/")).isTrue();
    // the capture attempt ADR-0025 closes: same address, issued by the partner provider
    assertThat(policy.grantsSystemAdmin(ADMIN, PARTNER_ISSUER)).isFalse();
    assertThat(policy.grantsSystemAdmin("other@opaa.local", DEFAULT_ISSUER)).isFalse();
  }

  /** The stored issuer keeps the provider's spelling; the comparison ignores trailing slashes. */
  @Test
  void aDefaultProviderStoredWithATrailingSlashStillMatchesItsIssuer() {
    OidcProvider auth0 =
        new OidcProvider(
            "Auth0",
            "https://tenant.eu.auth0.com/",
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    auth0.markDefault();
    when(repository.findByDefaultProviderTrue()).thenReturn(Optional.of(auth0));
    InitialAdminPolicy policy = policyFor(new AuthProperties("oidc", null, null, ADMIN));

    assertThat(policy.grantsSystemAdmin(ADMIN, "https://tenant.eu.auth0.com/")).isTrue();
    assertThat(policy.grantsSystemAdmin(ADMIN, "https://tenant.eu.auth0.com")).isTrue();
  }

  @Test
  void grantsNothingWhileNoDefaultProviderExists() {
    when(repository.findByDefaultProviderTrue()).thenReturn(Optional.empty());
    InitialAdminPolicy policy = policyFor(new AuthProperties("oidc", null, null, ADMIN));

    assertThat(policy.grantsSystemAdmin(ADMIN, DEFAULT_ISSUER)).isFalse();
  }

  @Test
  void inTheDevModeTheDevIssuerIsTheTrustedProvider() {
    AuthProperties dev =
        new AuthProperties(
            "dev", null, new AuthProperties.DevAuth("opaa-dev", "dev-admin", null), ADMIN);
    InitialAdminPolicy policy = policyFor(dev);

    assertThat(policy.grantsSystemAdmin(ADMIN, "opaa-dev")).isTrue();
    assertThat(policy.grantsSystemAdmin(ADMIN, DEFAULT_ISSUER)).isFalse();
  }

  @Test
  void aBlankInitialAdminAddressGrantsNothing() {
    InitialAdminPolicy policy = policyFor(new AuthProperties("oidc", null, null, "  "));

    assertThat(policy.grantsSystemAdmin(ADMIN, DEFAULT_ISSUER)).isFalse();
    assertThat(policy.grantsSystemAdmin(null, DEFAULT_ISSUER)).isFalse();
  }
}
