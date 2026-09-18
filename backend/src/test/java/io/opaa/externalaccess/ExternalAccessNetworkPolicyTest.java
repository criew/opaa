package io.opaa.externalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one place that decides whether a client address belongs to the channel's networks (#1717,
 * ADR-0035 Entscheidung 2). It fails closed in every direction the enforcement of #1721 will meet:
 * no networks configured, no address, a host name, a malformed literal.
 */
class ExternalAccessNetworkPolicyTest {

  private final ExternalAccessSettingsService settings = mock(ExternalAccessSettingsService.class);
  private final ExternalAccessNetworkPolicy policy = new ExternalAccessNetworkPolicy(settings);

  @Test
  void theDeliveredHomeNetworkCoversPrivateAddressesAndLoopbackButNotTheOpenInternet() {
    configure(ExternalAccessDefaults.ALLOWED_CIDRS);

    assertThat(policy.isAllowed("10.11.12.13")).isTrue();
    assertThat(policy.isAllowed("172.16.0.1")).isTrue();
    assertThat(policy.isAllowed("192.168.178.24")).isTrue();
    assertThat(policy.isAllowed("127.0.0.1")).isTrue();
    assertThat(policy.isAllowed("::1")).isTrue();
    assertThat(policy.isAllowed("fd00::1")).isTrue();

    assertThat(policy.isAllowed("8.8.8.8")).isFalse();
    assertThat(policy.isAllowed("172.32.0.1")).isFalse();
    assertThat(policy.isAllowed("2001:db8::1")).isFalse();
  }

  @Test
  void anEmptyListAllowsNobody() {
    configure(List.of());

    assertThat(policy.isAllowed("10.0.0.1")).isFalse();
    assertThat(policy.isAllowed("127.0.0.1")).isFalse();
  }

  @Test
  void aSingleAddressWithoutAPrefixIsARangeOfItsOwn() {
    configure(List.of("203.0.113.7"));

    assertThat(policy.isAllowed("203.0.113.7")).isTrue();
    assertThat(policy.isAllowed("203.0.113.8")).isFalse();
  }

  @Test
  void refusesAnythingThatIsNotANumericAddress() {
    configure(List.of("10.0.0.0/8"));

    assertThat(policy.isAllowed(null)).isFalse();
    assertThat(policy.isAllowed("")).isFalse();
    assertThat(policy.isAllowed("localhost")).isFalse();
    assertThat(policy.isAllowed("arbeitsplatz.stadt.example")).isFalse();
    assertThat(policy.isAllowed("10.0.0.300")).isFalse();
  }

  @Test
  void ignoresAStoredEntryThatIsNotAValidRangeInsteadOfFailingTheWholeCheck() {
    configure(List.of("kaputt", "10.0.0.0/8"));

    assertThat(policy.isAllowed("10.0.0.1")).isTrue();
    assertThat(policy.isAllowed("8.8.8.8")).isFalse();
  }

  @Test
  void aChangedListTakesEffectOnTheNextCallWithoutAnyInvalidation() {
    configure(List.of("10.0.0.0/8"));
    assertThat(policy.isAllowed("192.168.0.5")).isFalse();

    configure(List.of("192.168.0.0/16"));

    assertThat(policy.isAllowed("192.168.0.5")).isTrue();
    assertThat(policy.isAllowed("10.0.0.1")).isFalse();
  }

  @Test
  void aWhitespacePaddedAddressIsStillTheSameAddress() {
    configure(List.of("10.0.0.0/8"));

    assertThat(policy.isAllowed("  10.1.2.3  ")).isTrue();
  }

  private void configure(List<String> cidrs) {
    when(settings.allowedCidrs()).thenReturn(CidrList.normalize(cidrs));
  }
}
