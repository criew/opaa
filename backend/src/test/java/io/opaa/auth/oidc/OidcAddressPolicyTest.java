package io.opaa.auth.oidc;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.auth.AuthProperties;
import io.opaa.common.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link OidcAddressPolicy} (#1329, ADR-0025 Entscheidung 3): the bootstrap addresses are allowed
 * by scheme, host and port - never the bare host - the configured allowlist releases further hosts,
 * and everything else on a blocked range is refused with the variable named.
 */
class OidcAddressPolicyTest {

  private static OidcAddressPolicy policy(String issuer, String jwkSet, List<String> allowlist) {
    return OidcAddressPolicy.fromProperties(
        new AuthProperties.OidcAuth(
            null,
            "opaa-frontend",
            issuer,
            jwkSet,
            null,
            new AuthProperties.TargetValidation(true, allowlist)));
  }

  @Test
  void theBootstrapAddressesAreAllowedEvenOnLoopbackAndPrivateRanges() {
    OidcAddressPolicy policy =
        policy(
            "http://localhost:8180/realms/opaa",
            "http://127.0.0.1:8180/realms/opaa/protocol/openid-connect/certs",
            List.of());

    assertThatCode(() -> policy.requireAllowed("http://localhost:8180/realms/opaa", "Issuer-URI"))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                policy.requireAllowed(
                    "http://127.0.0.1:8180/realms/opaa/protocol/openid-connect/certs",
                    "JWK-Set-URI"))
        .doesNotThrowAnyException();
    // a different path on the same origin is the same trust decision
    assertThatCode(
            () -> policy.requireAllowed("http://localhost:8180/realms/other/certs", "JWK-Set-URI"))
        .doesNotThrowAnyException();
  }

  @Test
  void anotherPortOfTheBootstrapHostIsNotReleasedByTheException() {
    OidcAddressPolicy policy = policy("http://localhost:8180/realms/opaa", null, List.of());

    assertThatThrownBy(
            () -> policy.requireAllowed("http://localhost:8080/actuator/env", "JWK-Set-URI"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("JWK-Set-URI")
        .hasMessageContaining("OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void theSchemeIsPartOfTheBootstrapAddress() {
    OidcAddressPolicy policy = policy("https://localhost/realms/opaa", null, List.of());

    assertThatThrownBy(() -> policy.requireAllowed("http://localhost/realms/opaa", "Issuer-URI"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void theAllowlistReleasesFurtherInternalHosts() {
    OidcAddressPolicy policy =
        policy("https://idp.example/realms/a", null, List.of("keycloak-intern"));

    assertThatCode(
            () -> policy.requireAllowed("http://keycloak-intern:8080/realms/b", "Issuer-URI"))
        .doesNotThrowAnyException();
  }

  @Test
  void aBlockedAddressNamesTheVariableThatReleasesIt() {
    OidcAddressPolicy policy = policy("https://idp.example/realms/a", null, List.of());

    assertThatThrownBy(() -> policy.requireAllowed("http://169.254.169.254/latest", "Issuer-URI"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Issuer-URI")
        .hasMessageContaining("OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST");
  }

  @Test
  void aNonHttpAddressIsRefusedBeforeAnyLookup() {
    OidcAddressPolicy policy = policy("https://idp.example/realms/a", null, List.of());

    assertThatThrownBy(() -> policy.requireAllowed("ftp://idp.example/realms/a", "Issuer-URI"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("http");
  }
}
