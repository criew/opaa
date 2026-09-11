package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LocalAdminNetworkPolicy} (ADR-0033, Entscheidung 9): with {@code
 * OPAA_LOCAL_ADMIN_ALLOWED_CIDRS} set, a local {@code SYSTEM_ADMIN} signs in only from those
 * networks - IPv4 and IPv6, single addresses as well as ranges; an unknown or unparseable client
 * address is refused (fail closed). An empty list is no restriction at all.
 */
class LocalAdminNetworkPolicyTest {

  private static LocalAdminNetworkPolicy policyFor(List<String> cidrs) {
    return new LocalAdminNetworkPolicy(
        new LocalAuthProperties(
            "network-policy-test-secret-0123456789-abcdefghij",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            cidrs));
  }

  @Test
  void anEmptyListRestrictsNothing() {
    LocalAdminNetworkPolicy policy = policyFor(List.of());

    assertThat(policy.isRestricted()).isFalse();
    assertThat(policy.permitsAdminSignIn("203.0.113.9")).isTrue();
    assertThat(policy.permitsAdminSignIn(null)).isTrue();
  }

  @Test
  void permitsOnlyAddressesInsideTheConfiguredNetworks() {
    LocalAdminNetworkPolicy policy =
        policyFor(List.of("10.0.0.0/8", " 2001:db8::/32 ", "192.168.1.5"));

    assertThat(policy.isRestricted()).isTrue();
    assertThat(policy.permitsAdminSignIn("10.1.2.3")).isTrue();
    assertThat(policy.permitsAdminSignIn("2001:db8:0:1::7")).isTrue();
    assertThat(policy.permitsAdminSignIn("192.168.1.5")).isTrue();
    assertThat(policy.permitsAdminSignIn("192.168.1.6")).isFalse();
    assertThat(policy.permitsAdminSignIn("11.0.0.1")).isFalse();
    assertThat(policy.permitsAdminSignIn("2001:db9::1")).isFalse();
  }

  @Test
  void anUnknownOrUnparseableClientAddressIsRefusedWhileRestricted() {
    LocalAdminNetworkPolicy policy = policyFor(List.of("10.0.0.0/8"));

    assertThat(policy.permitsAdminSignIn(null)).isFalse();
    assertThat(policy.permitsAdminSignIn("")).isFalse();
    assertThat(policy.permitsAdminSignIn("kein-netz")).isFalse();
  }
}
