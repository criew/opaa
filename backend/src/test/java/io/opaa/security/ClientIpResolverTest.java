package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link TrustedProxyClientIpResolver} (ADR-0033, Entscheidung 9): {@code X-Forwarded-For} counts
 * only when the connection itself comes from a trusted proxy, and then the client is the first
 * entry from the right that is not a trusted proxy - the entry the nearest trusted hop appended,
 * which no client can forge. Without a trusted proxy (the default) the header is ignored entirely.
 */
class ClientIpResolverTest {

  private static final String XFF = "X-Forwarded-For";

  private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/local/login");
    request.setRemoteAddr(remoteAddr);
    if (forwardedFor != null) {
      request.addHeader(XFF, forwardedFor);
    }
    return request;
  }

  @Test
  void withoutTrustedProxiesTheHeaderIsIgnored() {
    var resolver = new TrustedProxyClientIpResolver(List.of());

    assertThat(resolver.hasTrustedProxies()).isFalse();
    assertThat(resolver.resolve(request("203.0.113.7", "198.51.100.1"))).isEqualTo("203.0.113.7");
    assertThat(resolver.resolve(request("203.0.113.7", null))).isEqualTo("203.0.113.7");
  }

  @Test
  void aForgedHeaderFromAnUntrustedConnectionIsIgnored() {
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("203.0.113.7", "198.51.100.1, 10.0.0.9")))
        .isEqualTo("203.0.113.7");
    assertThat(resolver.isTrustedProxy("203.0.113.7")).isFalse();
  }

  @Test
  void behindATrustedProxyTheSingleHopIsTheClient() {
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.0.0.9", "198.51.100.1"))).isEqualTo("198.51.100.1");
    assertThat(resolver.resolve(request("10.0.0.9", "  198.51.100.1  "))).isEqualTo("198.51.100.1");
    assertThat(resolver.isTrustedProxy("10.0.0.9")).isTrue();
  }

  @Test
  void theClientIsTheFirstUntrustedEntryFromTheRightNotTheLeftmost() {
    // an attacker sent "X-Forwarded-For: 9.9.9.9"; the proxy appended what it saw (the client)
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.0.0.9", "9.9.9.9, 198.51.100.1")))
        .isEqualTo("198.51.100.1");
  }

  @Test
  void trustedIntermediateHopsAreSkipped() {
    // outer proxy 10.1.1.1 appended the client, inner proxy 10.0.0.9 appended the outer proxy
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.0.0.9", "9.9.9.9, 198.51.100.1, 10.1.1.1")))
        .isEqualTo("198.51.100.1");
  }

  @Test
  void whenEveryHopIsTrustedTheLeftmostEntryIsTheOrigin() {
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.0.0.9", "10.2.2.2, 10.1.1.1"))).isEqualTo("10.2.2.2");
  }

  @Test
  void aBlankHeaderBehindATrustedProxyFallsBackToTheConnection() {
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.0.0.9", "   "))).isEqualTo("10.0.0.9");
    assertThat(resolver.resolve(request("10.0.0.9", " , "))).isEqualTo("10.0.0.9");
    assertThat(resolver.resolve(request("10.0.0.9", null))).isEqualTo("10.0.0.9");
  }

  @Test
  void anEntryThatIsNoNumericAddressFallsBackToTheConnection() {
    // the result is always a numeric address: never a DNS lookup on header text, never header
    // text as a bucket key or as the input of the administrator network check
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));

    assertThat(resolver.resolve(request("10.0.0.9", "evil.example, 10.1.1.1")))
        .isEqualTo("10.0.0.9");
    assertThat(resolver.resolve(request("10.0.0.9", "1.2.3"))).isEqualTo("10.0.0.9");
    assertThat(resolver.resolve(request("10.0.0.9", "198.51.100.1:8080"))).isEqualTo("10.0.0.9");
    assertThat(resolver.resolve(request("10.0.0.9", "256.1.1.1"))).isEqualTo("10.0.0.9");
    assertThat(resolver.resolve(request("10.0.0.9", "2001:db8::zz"))).isEqualTo("10.0.0.9");
    // garbage left of the client is never reached - the walk stops at the client
    assertThat(resolver.resolve(request("10.0.0.9", "evil.example, 198.51.100.1")))
        .isEqualTo("198.51.100.1");
  }

  @Test
  void ipv6ProxiesAndClientsAreResolvedTheSameWay() {
    var resolver = new TrustedProxyClientIpResolver(List.of("2001:db8::/32", "10.0.0.9"));

    assertThat(resolver.resolve(request("2001:db8:0:1::7", "2001:db8:ffff::1, 2a02:1::5")))
        .isEqualTo("2a02:1::5");
    assertThat(resolver.resolve(request("10.0.0.9", "2a02:1::5"))).isEqualTo("2a02:1::5");
    assertThat(resolver.resolve(request("2001:db9::1", "2a02:1::5"))).isEqualTo("2001:db9::1");
  }

  @Test
  void severalHeaderLinesFormOneChain() {
    // RFC 9110: a proxy may add its own X-Forwarded-For line instead of appending - the lines are
    // one chain in order, so the client is still the entry the nearest trusted hop wrote
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));
    MockHttpServletRequest request = request("10.0.0.9", "9.9.9.9");
    request.addHeader(XFF, "198.51.100.1, 10.1.1.1");

    assertThat(resolver.forwardedFor(request)).isEqualTo("9.9.9.9, 198.51.100.1, 10.1.1.1");
    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.1");
    assertThat(resolver.forwardedFor(request("10.0.0.9", null))).isNull();
  }

  @Test
  void theConnectionAddressAndTheHeaderAreReadBeneathEveryRequestWrapper() {
    // regression guard: Spring's ForwardedHeaderFilter (forward-headers-strategy: framework) wraps
    // the request with getRemoteAddr() rewritten from the leftmost X-Forwarded-For entry and the
    // header hidden - the resolver must see the connection and the raw header underneath
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));
    MockHttpServletRequest root = request("10.0.0.9", "9.9.9.9, 198.51.100.1");
    HttpServletRequest wrapped =
        new HttpServletRequestWrapper(new HttpServletRequestWrapper(root)) {
          @Override
          public String getRemoteAddr() {
            return "9.9.9.9";
          }

          @Override
          public String getHeader(String name) {
            return XFF.equalsIgnoreCase(name) ? null : super.getHeader(name);
          }
        };

    assertThat(resolver.resolve(wrapped)).isEqualTo("198.51.100.1");
    assertThat(resolver.remoteAddress(wrapped)).isEqualTo("10.0.0.9");
    assertThat(resolver.forwardedFor(wrapped)).isEqualTo("9.9.9.9, 198.51.100.1");

    var untrusting = new TrustedProxyClientIpResolver(List.of());
    assertThat(untrusting.resolve(wrapped)).isEqualTo("10.0.0.9");
  }

  @Test
  void aRequestWithoutAnAddressResolvesToNull() {
    var resolver = new TrustedProxyClientIpResolver(List.of("10.0.0.0/8"));
    MockHttpServletRequest request = request("10.0.0.9", "198.51.100.1");
    request.setRemoteAddr(null);

    assertThat(resolver.resolve(request)).isNull();
  }
}
