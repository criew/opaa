package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link OidcAddressPolicy} (ADR-0025 Entscheidung 3): the configured allowlist releases internal
 * hosts, and everything else on a blocked range is refused with the variable named - no address is
 * exempt, also not the one of the installation's provider (ADR-0060).
 */
class OidcAddressPolicyTest {

  private static OidcAddressPolicy policy(List<String> allowlist) {
    return OidcAddressPolicy.fromProperties(
        new AuthProperties.OidcAuth(new AuthProperties.TargetValidation(true, allowlist)));
  }

  @Test
  void loopbackIsRefusedWithoutAnAllowlistEntry() {
    OidcAddressPolicy policy = policy(List.of());

    assertThatThrownBy(
            () -> policy.requireAllowed("http://localhost:8180/realms/opaa", "Issuer-URI"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Issuer-URI")
        .hasMessageContaining("OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void theAllowlistReleasesInternalHosts() {
    OidcAddressPolicy policy = policy(List.of("keycloak-intern", "localhost"));

    assertThatCode(
            () -> policy.requireAllowed("http://keycloak-intern:8080/realms/b", "Issuer-URI"))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> policy.requireAllowed("http://localhost:8180/realms/opaa/certs", "JWK-Set-URI"))
        .doesNotThrowAnyException();
  }

  @Test
  void aBlockedAddressNamesTheVariableThatReleasesIt() {
    OidcAddressPolicy policy = policy(List.of());

    assertThatThrownBy(() -> policy.requireAllowed("http://169.254.169.254/latest", "Issuer-URI"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Issuer-URI")
        .hasMessageContaining("OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void aNonHttpAddressIsRefusedBeforeAnyLookup() {
    OidcAddressPolicy policy = policy(List.of());

    assertThatThrownBy(() -> policy.requireAllowed("ftp://idp.example/realms/a", "Issuer-URI"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("http");
  }
}
