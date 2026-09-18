package io.opaa.externalaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.security.TrustedProxyClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The one place that decides whether a client address belongs to the channel's networks (#1717,
 * ADR-0035 Entscheidung 2). It fails closed in every direction the enforcement of #1721 will meet:
 * no networks configured, no address, a host name, a malformed literal - and it takes the address
 * from {@link TrustedProxyClientIpResolver}, so a client cannot choose it through {@code
 * X-Forwarded-For}.
 */
class ExternalAccessNetworkPolicyTest {

  private static final String HOUSE_PROXY = "10.9.0.1";

  private final ExternalAccessSettingsService settings = mock(ExternalAccessSettingsService.class);

  private ExternalAccessNetworkPolicy policy(String... trustedProxyCidrs) {
    return new ExternalAccessNetworkPolicy(
        settings, new TrustedProxyClientIpResolver(List.of(trustedProxyCidrs)));
  }

  @Test
  void theDeliveredHomeNetworkCoversPrivateAddressesAndLoopbackButNotTheOpenInternet() {
    configure(ExternalAccessDefaults.ALLOWED_CIDRS);
    ExternalAccessNetworkPolicy policy = policy();

    assertThat(policy.isAllowed(from("10.11.12.13"))).isTrue();
    assertThat(policy.isAllowed(from("172.16.0.1"))).isTrue();
    assertThat(policy.isAllowed(from("192.168.178.24"))).isTrue();
    assertThat(policy.isAllowed(from("127.0.0.1"))).isTrue();
    assertThat(policy.isAllowed(from("::1"))).isTrue();
    assertThat(policy.isAllowed(from("fd00::1"))).isTrue();

    assertThat(policy.isAllowed(from("8.8.8.8"))).isFalse();
    assertThat(policy.isAllowed(from("172.32.0.1"))).isFalse();
    assertThat(policy.isAllowed(from("2001:db8::1"))).isFalse();
  }

  /** Behind a trusted proxy the forwarded address decides - that is the operating shape. */
  @Test
  void behindATrustedProxyTheForwardedAddressDecides() {
    configure(List.of("10.0.0.0/8"));
    ExternalAccessNetworkPolicy policy = policy("10.9.0.0/16");

    assertThat(policy.isAllowed(forwarded(HOUSE_PROXY, "10.4.5.6"))).isTrue();
    assertThat(policy.isAllowed(forwarded(HOUSE_PROXY, "203.0.113.9"))).isFalse();
    assertThat(policy.isAllowed(forwarded(HOUSE_PROXY, "203.0.113.9, 10.9.0.1"))).isFalse();
  }

  /**
   * Regression guard against the failure the whole restriction stands or falls with: a client that
   * writes its own {@code X-Forwarded-For} from outside must not be able to claim a house address,
   * and a request that merely <em>arrives</em> from the proxy must not pass because the proxy
   * itself sits in the house network.
   */
  @Test
  void anUntrustedCallerCannotChooseItsAddressThroughTheHeader() {
    configure(List.of("10.0.0.0/8"));
    ExternalAccessNetworkPolicy withoutTrustedProxies = policy();

    assertThat(withoutTrustedProxies.isAllowed(forwarded("203.0.113.9", "10.4.5.6"))).isFalse();

    ExternalAccessNetworkPolicy withTrustedProxy = policy("10.9.0.0/16");
    assertThat(withTrustedProxy.isAllowed(forwarded("203.0.113.9", "10.4.5.6"))).isFalse();
  }

  @Test
  void anEmptyListAllowsNobody() {
    configure(List.of());
    ExternalAccessNetworkPolicy policy = policy();

    assertThat(policy.isAllowed(from("10.0.0.1"))).isFalse();
    assertThat(policy.isAllowed(from("127.0.0.1"))).isFalse();
  }

  @Test
  void aSingleAddressWithoutAPrefixIsARangeOfItsOwn() {
    configure(List.of("203.0.113.7"));
    ExternalAccessNetworkPolicy policy = policy();

    assertThat(policy.isAllowed(from("203.0.113.7"))).isTrue();
    assertThat(policy.isAllowed(from("203.0.113.8"))).isFalse();
  }

  @Test
  void refusesAnythingThatIsNotANumericAddress() {
    configure(List.of("10.0.0.0/8"));
    ExternalAccessNetworkPolicy policy = policy();

    assertThat(policy.isAllowed((String) null)).isFalse();
    assertThat(policy.isAllowed("")).isFalse();
    assertThat(policy.isAllowed("localhost")).isFalse();
    assertThat(policy.isAllowed("arbeitsplatz.stadt.example")).isFalse();
    assertThat(policy.isAllowed("10.0.0.300")).isFalse();
  }

  @Test
  void ignoresAStoredEntryThatIsNotAValidRangeInsteadOfFailingTheWholeCheck() {
    configure(List.of("kaputt", "10.0.0.0/8"));
    ExternalAccessNetworkPolicy policy = policy();

    assertThat(policy.isAllowed(from("10.0.0.1"))).isTrue();
    assertThat(policy.isAllowed(from("8.8.8.8"))).isFalse();
  }

  @Test
  void aChangedListTakesEffectOnTheNextCallWithoutAnyInvalidation() {
    configure(List.of("10.0.0.0/8"));
    ExternalAccessNetworkPolicy policy = policy();
    assertThat(policy.isAllowed(from("192.168.0.5"))).isFalse();

    configure(List.of("192.168.0.0/16"));

    assertThat(policy.isAllowed(from("192.168.0.5"))).isTrue();
    assertThat(policy.isAllowed(from("10.0.0.1"))).isFalse();
  }

  @Test
  void aWhitespacePaddedAddressIsStillTheSameAddress() {
    configure(List.of("10.0.0.0/8"));

    assertThat(policy().isAllowed("  10.1.2.3  ")).isTrue();
  }

  private void configure(List<String> cidrs) {
    when(settings.allowedCidrs()).thenReturn(CidrList.normalize(cidrs));
  }

  private static HttpServletRequest from(String remoteAddress) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddress);
    return request;
  }

  private static HttpServletRequest forwarded(String remoteAddress, String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remoteAddress);
    request.addHeader(TrustedProxyClientIpResolver.X_FORWARDED_FOR, forwardedFor);
    return request;
  }
}
