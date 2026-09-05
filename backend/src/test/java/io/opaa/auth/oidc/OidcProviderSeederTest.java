package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.auth.AuthProperties;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link OidcProviderSeeder} (#1329, ADR-0025 Entscheidung 3): the one-time takeover of the {@code
 * OPAA_OIDC_*} configuration into the first, enabled default provider row - guarded by the seed
 * marker, never by "is the table empty?", and never in the {@code dev} mode, which knows no
 * providers.
 */
class OidcProviderSeederTest {

  private final OidcProviderRepository repository = mock(OidcProviderRepository.class);
  private final OidcProviderSeedMarkerRepository markerRepository =
      mock(OidcProviderSeedMarkerRepository.class);

  private OidcProviderSeeder seederFor(AuthProperties properties) {
    return new OidcProviderSeeder(repository, markerRepository, properties);
  }

  private static AuthProperties oidc(
      String issuer, String jwkSetUri, String authority, String clientId) {
    return new AuthProperties(
        "oidc",
        new AuthProperties.OidcAuth(authority, clientId, issuer, jwkSetUri, null, null),
        null,
        "admin@opaa.local");
  }

  @Test
  void seedsTheEnvironmentIssuerAsTheEnabledDefaultProviderAndWritesTheMarker() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    when(repository.count()).thenReturn(0L);

    seederFor(
            oidc(
                "http://localhost:8180/realms/opaa",
                "http://keycloak:8180/realms/opaa/protocol/openid-connect/certs",
                "http://localhost:8180/realms/opaa",
                "opaa-frontend"))
        .seedIfNeeded();

    ArgumentCaptor<OidcProvider> captor = ArgumentCaptor.forClass(OidcProvider.class);
    verify(repository).save(captor.capture());
    OidcProvider seeded = captor.getValue();
    assertThat(seeded.getDisplayName()).isEqualTo(OidcProviderSeeder.SEEDED_DISPLAY_NAME);
    assertThat(seeded.getIssuerUri()).isEqualTo("http://localhost:8180/realms/opaa");
    assertThat(seeded.getJwkSetUri())
        .isEqualTo("http://keycloak:8180/realms/opaa/protocol/openid-connect/certs");
    assertThat(seeded.getClientId()).isEqualTo("opaa-frontend");
    assertThat(seeded.isEnabled()).isTrue();
    assertThat(seeded.isDefaultProvider()).isTrue();
    assertThat(seeded.getClaimMapping()).isEqualTo(OidcClaimMapping.keycloakDefaults());
    verify(markerRepository).save(any(OidcProviderSeedMarker.class));
  }

  @Test
  void anAuthorityThatDiffersFromTheIssuerIsIgnoredInFavourOfTheIssuer() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    when(repository.count()).thenReturn(0L);

    seederFor(
            oidc(
                "https://idp.example/realms/opaa",
                null,
                "https://idp.example/realms/other",
                "opaa-frontend"))
        .seedIfNeeded();

    ArgumentCaptor<OidcProvider> captor = ArgumentCaptor.forClass(OidcProvider.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getIssuerUri()).isEqualTo("https://idp.example/realms/opaa");
    assertThat(captor.getValue().getJwkSetUri()).isNull();
  }

  @Test
  void doesNothingOnceTheTakeoverWasAttempted() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(true);

    seederFor(oidc("https://idp.example/realms/opaa", null, null, "opaa-frontend")).seedIfNeeded();

    verify(repository, never()).save(any());
    verify(markerRepository, never()).save(any());
  }

  @Test
  void neverSeedsInTheDevModeAndLeavesTheMarkerUntouched() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    AuthProperties dev =
        new AuthProperties(
            "dev",
            new AuthProperties.OidcAuth(
                null, "opaa-frontend", "https://idp.example/realms/x", null, null, null),
            null,
            "admin@opaa.local");

    seederFor(dev).seedIfNeeded();

    verify(repository, never()).save(any());
    // a later switch to the oidc profile must still be able to take the environment over
    verify(markerRepository, never()).save(any());
  }

  @Test
  void forcedBootstrapRestoresTheEnvironmentProviderDespiteTheMarker() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(true);
    OidcProvider mistyped =
        new OidcProvider(
            "Verzeichnisdienst",
            "https://idp.example/realms/opaa",
            "old-client",
            null,
            OidcClaimMapping.keycloakDefaults());
    mistyped.disable();
    OidcProvider partner =
        OidcProviderServiceTest.provider("Partner", "https://idp.example/realms/b", true, true);
    when(repository.findByNormalizedIssuerUri("https://idp.example/realms/opaa"))
        .thenReturn(Optional.of(mistyped));
    when(repository.findByDefaultProviderTrue()).thenReturn(Optional.of(partner));
    AuthProperties forced =
        new AuthProperties(
            "oidc",
            new AuthProperties.OidcAuth(
                null, "opaa-frontend", "https://idp.example/realms/opaa", null, "force", null),
            null,
            "admin@opaa.local");

    seederFor(forced).seedIfNeeded();

    assertThat(mistyped.isEnabled()).isTrue();
    assertThat(mistyped.isDefaultProvider()).isTrue();
    assertThat(mistyped.getClientId()).isEqualTo("opaa-frontend");
    assertThat(partner.isDefaultProvider()).isFalse();
    verify(repository).save(mistyped);
    verify(markerRepository, never()).save(any());
  }

  @Test
  void forcedBootstrapCreatesTheEnvironmentProviderWhenNoneHasItsIssuer() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(true);
    when(repository.findByNormalizedIssuerUri(any())).thenReturn(Optional.empty());
    when(repository.findByDefaultProviderTrue()).thenReturn(Optional.empty());
    AuthProperties forced =
        new AuthProperties(
            "oidc",
            new AuthProperties.OidcAuth(
                null, "opaa-frontend", "https://idp.example/realms/opaa", null, "FORCE", null),
            null,
            "admin@opaa.local");

    seederFor(forced).seedIfNeeded();

    ArgumentCaptor<OidcProvider> captor = ArgumentCaptor.forClass(OidcProvider.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getIssuerUri()).isEqualTo("https://idp.example/realms/opaa");
    assertThat(captor.getValue().isDefaultProvider()).isTrue();
  }

  @Test
  void writesOnlyTheMarkerWhenProvidersAlreadyExist() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    when(repository.count()).thenReturn(2L);

    seederFor(oidc("https://idp.example/realms/opaa", null, null, "opaa-frontend")).seedIfNeeded();

    verify(repository, never()).save(any());
    verify(markerRepository).save(any(OidcProviderSeedMarker.class));
  }

  @Test
  void leavesNoMarkerWhenTheEnvironmentNamesNoIssuerSoALaterBootstrapStillWorks() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    when(repository.count()).thenReturn(0L);

    seederFor(oidc("  ", null, null, "opaa-frontend")).seedIfNeeded();

    verify(repository, never()).save(any());
    verify(markerRepository, never()).save(any());
  }

  @Test
  void leavesNoMarkerWhenTheClientIdIsMissingSoALaterBootstrapStillWorks() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    when(repository.count()).thenReturn(0L);

    // an unset OPAA_OIDC_CLIENT_ID binds to the empty string, not to null
    seederFor(oidc("https://idp.example/realms/opaa", null, null, "")).seedIfNeeded();

    verify(repository, never()).save(any());
    verify(markerRepository, never()).save(any());
  }

  @Test
  void leavesNoMarkerWhenTheIssuerIsNoHttpAddress() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    when(repository.count()).thenReturn(0L);

    seederFor(oidc("idp.example/realms/opaa", null, null, "opaa-frontend")).seedIfNeeded();

    verify(repository, never()).save(any());
    verify(markerRepository, never()).save(any());
  }

  @Test
  void forcedBootstrapWithAnIncompleteEnvironmentChangesNothing() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(true);
    AuthProperties forced =
        new AuthProperties(
            "oidc",
            new AuthProperties.OidcAuth(
                null, "", "https://idp.example/realms/opaa", null, "force", null),
            null,
            "admin@opaa.local");

    seederFor(forced).seedIfNeeded();

    verify(repository, never()).save(any());
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void theIssuerIsTakenOverExactlyAsTheProviderMintsIt() {
    when(markerRepository.seedAlreadyAttempted()).thenReturn(false);
    when(repository.count()).thenReturn(0L);

    seederFor(oidc(" https://tenant.eu.auth0.com/ ", null, null, "opaa-frontend")).seedIfNeeded();

    ArgumentCaptor<OidcProvider> captor = ArgumentCaptor.forClass(OidcProvider.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getIssuerUri()).isEqualTo("https://tenant.eu.auth0.com/");
  }
}
