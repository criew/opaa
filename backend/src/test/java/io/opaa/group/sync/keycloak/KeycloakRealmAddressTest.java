package io.opaa.group.sync.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import org.junit.jupiter.api.Test;

/**
 * Deriving realm and admin API base from the provider's issuer (#1817). The realm is never
 * configurable: that is what makes it impossible to read a different realm than the one this
 * provider's accounts come from (ADR-0025).
 */
class KeycloakRealmAddressTest {

  @Test
  void theRealmAndTheBaseComeFromTheIssuer() {
    KeycloakRealmAddress address =
        KeycloakRealmAddress.of("https://kc.behoerde.example/realms/mitarbeitende", null);

    assertThat(address.realm()).isEqualTo("mitarbeitende");
    assertThat(address.baseUrl()).isEqualTo("https://kc.behoerde.example");
  }

  /** The pre-17 Keycloak layout keeps its path prefix; only the /realms/<realm> tail is dropped. */
  @Test
  void aLegacyAuthPrefixSurvivesTheDerivation() {
    KeycloakRealmAddress address =
        KeycloakRealmAddress.of("https://kc.behoerde.example/auth/realms/haus", null);

    assertThat(address.baseUrl()).isEqualTo("https://kc.behoerde.example/auth");
    assertThat(address.realm()).isEqualTo("haus");
  }

  @Test
  void aTrailingSlashIsIgnored() {
    assertThat(KeycloakRealmAddress.of("https://kc.example/realms/haus/", null).realm())
        .isEqualTo("haus");
  }

  @Test
  void aPercentEncodedRealmArrivesDecoded() {
    assertThat(KeycloakRealmAddress.of("https://kc.example/realms/Haus%20A", null).realm())
        .isEqualTo("Haus A");
  }

  /**
   * The override replaces the address the backend calls - the Compose split between the issuer the
   * browser reaches and the service name the backend reaches - but never the realm.
   */
  @Test
  void anOverrideReplacesOnlyTheBaseAddress() {
    KeycloakRealmAddress address =
        KeycloakRealmAddress.of("https://kc.example/realms/haus", "http://keycloak:8180/");

    assertThat(address.baseUrl()).isEqualTo("http://keycloak:8180");
    assertThat(address.realm()).isEqualTo("haus");
  }

  @Test
  void aBlankOverrideIsTreatedAsAbsent() {
    assertThat(KeycloakRealmAddress.of("https://kc.example/realms/haus", "   ").baseUrl())
        .isEqualTo("https://kc.example");
  }

  @Test
  void anIssuerThatIsNoRealmAddressIsRefused() {
    assertThatThrownBy(() -> KeycloakRealmAddress.of("https://entra.example/tenant/v2.0", null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Keycloak-Realm-Adresse");
  }

  @Test
  void anIssuerWithoutARealmNameIsRefused() {
    assertThatThrownBy(() -> KeycloakRealmAddress.of("https://kc.example/realms/", null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Keycloak-Realm-Adresse");
  }

  @Test
  void theLocalIssuerUrnIsRefused() {
    assertThatThrownBy(() -> KeycloakRealmAddress.of("urn:opaa:local", null))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void anOverrideThatIsNoHttpAddressIsRefused() {
    assertThatThrownBy(
            () -> KeycloakRealmAddress.of("https://kc.example/realms/haus", "ftp://kc.example"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("http(s)");
  }
}
