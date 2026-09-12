package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ProviderType;
import io.opaa.auth.LocalIssuer;
import org.junit.jupiter.api.Test;

/**
 * The {@code LOCAL} provider row (ADR-0033 Entscheidung 4) as {@link OidcProvider} models it: fixed
 * issuer, no client id, switched off until an administrator enables it, never the default, and
 * without editable connection details.
 */
class OidcProviderLocalRowTest {

  @Test
  void aRegularProviderIsOfTypeOidc() {
    OidcProvider provider =
        new OidcProvider("Verzeichnisdienst", "https://idp.example/realms/a", "opaa", null, null);

    assertThat(provider.getProviderType()).isEqualTo(ProviderType.OIDC);
    assertThat(provider.isLocal()).isFalse();
    assertThat(provider.getClientId()).isEqualTo("opaa");
  }

  @Test
  void theLocalRowCarriesTheAdrInvariants() {
    OidcProvider local = OidcProvider.localProvider("Lokale Konten");

    assertThat(local.getProviderType()).isEqualTo(ProviderType.LOCAL);
    assertThat(local.isLocal()).isTrue();
    assertThat(local.getIssuerUri()).isEqualTo(LocalIssuer.URN);
    assertThat(local.getClientId()).isNull();
    assertThat(local.getJwkSetUri()).isNull();
    assertThat(local.isEnabled()).isFalse();
    assertThat(local.isDefaultProvider()).isFalse();
    assertThat(local.getDisplayName()).isEqualTo("Lokale Konten");
    assertThat(local.getClaimMapping()).isEqualTo(OidcClaimMapping.keycloakDefaults());
  }

  @Test
  void theLocalRowRefusesEditsThatWouldBreakItsInvariants() {
    OidcProvider local = OidcProvider.localProvider("Lokale Konten");

    assertThatThrownBy(() -> local.replaceDetails("X", "https://x", "c", null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(local::markDefault).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theLocalRowCanBeRenamedAndSwitched() {
    OidcProvider local = OidcProvider.localProvider("Lokale Konten");

    local.rename("Konten der Stadt");
    local.enable();

    assertThat(local.getDisplayName()).isEqualTo("Konten der Stadt");
    assertThat(local.isEnabled()).isTrue();
    assertThat(local.getIssuerUri()).isEqualTo(LocalIssuer.URN);
  }

  @Test
  void theLocalRowSharesNoDecoderInputsWithAnyOidcRow() {
    OidcProvider local = OidcProvider.localProvider("Lokale Konten");
    OidcProvider oidc = new OidcProvider("X", LocalIssuer.URN, "opaa", null, null);

    assertThat(local.hasSameDecoderInputsAs(oidc)).isFalse();
    assertThat(local.hasSameDecoderInputsAs(OidcProvider.localProvider("Y"))).isTrue();
  }
}
