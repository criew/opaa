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
 * {@link TrustedProvider}: the default provider in {@code oidc}, the dev issuer in {@code dev} -
 * since ADR-0033 the binding of the directory synchronisation only; the initial administrator rule
 * consults it no more ({@code InitialAdminPolicyTest}).
 */
class TrustedProviderTest {

  private final OidcProviderRepository repository = mock(OidcProviderRepository.class);

  @Test
  void inTheOidcModeTheDefaultProviderIsTrustedAndComparedWithoutTrailingSlashes() {
    OidcProvider standard =
        new OidcProvider(
            "Beschäftigte",
            "https://idp.example/realms/a/",
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    standard.markDefault();
    when(repository.findByDefaultProviderTrueAndEnabledTrue()).thenReturn(Optional.of(standard));
    TrustedProvider trusted =
        new TrustedProvider(new AuthProperties("oidc", null, null, null), repository);

    assertThat(trusted.issuer()).contains("https://idp.example/realms/a/");
    assertThat(trusted.matches("https://idp.example/realms/a")).isTrue();
    assertThat(trusted.matches("https://partner.example/realms/b")).isFalse();
    assertThat(trusted.matches(null)).isFalse();
  }

  /**
   * Regression guard for #1832: the last enabled OIDC provider keeps {@code is_default} when it is
   * switched off (ADR-0033, Entscheidung 4), so the lookup has to ask for {@code enabled} as well -
   * otherwise a disabled provider would stay the anchor the directory synchronisation resolves
   * members through.
   */
  @Test
  void aDisabledDefaultProviderIsNotTrusted() {
    OidcProvider standard =
        new OidcProvider(
            "Beschäftigte",
            "https://idp.example/realms/a/",
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    standard.markDefault();
    standard.disable();
    when(repository.findByDefaultProviderTrue()).thenReturn(Optional.of(standard));
    when(repository.findByDefaultProviderTrueAndEnabledTrue()).thenReturn(Optional.empty());
    TrustedProvider trusted =
        new TrustedProvider(new AuthProperties("oidc", null, null, null), repository);

    assertThat(trusted.issuer()).isEmpty();
    assertThat(trusted.matches("https://idp.example/realms/a")).isFalse();
  }

  @Test
  void withoutADefaultProviderNothingIsTrusted() {
    when(repository.findByDefaultProviderTrueAndEnabledTrue()).thenReturn(Optional.empty());
    TrustedProvider trusted =
        new TrustedProvider(new AuthProperties("oidc", null, null, null), repository);

    assertThat(trusted.issuer()).isEmpty();
    assertThat(trusted.matches("https://idp.example/realms/a")).isFalse();
  }

  @Test
  void inTheDevModeTheDevIssuerIsTrusted() {
    TrustedProvider trusted =
        new TrustedProvider(
            new AuthProperties(
                "dev", null, new AuthProperties.DevAuth("opaa-dev", "dev-admin", null), null),
            repository);

    assertThat(trusted.issuer()).contains("opaa-dev");
    assertThat(trusted.matches("opaa-dev")).isTrue();
    assertThat(trusted.matches("https://idp.example/realms/a")).isFalse();
  }
}
