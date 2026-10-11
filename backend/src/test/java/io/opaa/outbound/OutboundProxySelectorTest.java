package io.opaa.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundProxySelectorTest {

  private static final ProxyAddress PROXY = new ProxyAddress("proxy.example.org", 3128);

  private final OutboundProxySelector selector = OutboundProxySelector.instance();

  @BeforeEach
  void routeThroughTheProxy() {
    selector.apply(
        new OutboundProxySelector.Snapshot(
            PROXY, ProxyExclusions.of(List.of("intranet.example", "10.0.0.0/8"))));
  }

  @AfterEach
  void goDirectAgain() {
    selector.apply(OutboundProxySelector.Snapshot.direct());
  }

  @Test
  void httpAndHttpsTargetsGoThroughTheProxy() {
    assertThat(selector.select(URI.create("https://api.example.com/v1")))
        .containsExactly(new Proxy(Proxy.Type.HTTP, PROXY.socketAddress()));
    assertThat(selector.select(URI.create("HTTP://feed.example.com/rss")))
        .containsExactly(new Proxy(Proxy.Type.HTTP, PROXY.socketAddress()));
  }

  @Test
  void theProxyAddressIsUnresolvedSoItIsLookedUpPerConnection() {
    Proxy proxy = selector.select(URI.create("https://api.example.com")).get(0);

    assertThat(((InetSocketAddress) proxy.address()).isUnresolved()).isTrue();
    assertThat(((InetSocketAddress) proxy.address()).getHostString())
        .isEqualTo("proxy.example.org");
  }

  /** The invariant: a raw socket - SMB, SMTP, JDBC - never goes through the HTTP proxy. */
  @Test
  void aSocketTargetIsAlwaysDirect() {
    assertThat(selector.select(URI.create("socket://fileserver.example.com:445")))
        .containsExactly(Proxy.NO_PROXY);
    assertThat(selector.select(URI.create("socket://db.example.com:5432")))
        .containsExactly(Proxy.NO_PROXY);
    assertThat(selector.select(URI.create("socket://mail.example.com:587")))
        .containsExactly(Proxy.NO_PROXY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ftp://files.example.com/", "ws://chat.example.com/", "smb://nas/x"})
  void everyOtherSchemeIsDirect(String target) {
    assertThat(selector.select(URI.create(target))).containsExactly(Proxy.NO_PROXY);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost:8080/",
        "http://app.localhost/",
        "http://127.0.0.1:5432/",
        "http://127.10.0.1/",
        "http://[::1]:8080/"
      })
  void loopbackIsAlwaysDirect(String target) {
    assertThat(selector.select(URI.create(target))).containsExactly(Proxy.NO_PROXY);
  }

  @Test
  void anExcludedTargetIsDirect() {
    assertThat(selector.select(URI.create("https://wiki.intranet.example/")))
        .containsExactly(Proxy.NO_PROXY);
    assertThat(selector.select(URI.create("http://10.1.2.3/"))).containsExactly(Proxy.NO_PROXY);
  }

  @Test
  void withoutAProxyEverythingIsDirect() {
    selector.apply(OutboundProxySelector.Snapshot.direct());

    assertThat(selector.select(URI.create("https://api.example.com/")))
        .containsExactly(Proxy.NO_PROXY);
    assertThat(selector.isProxied("api.example.com")).isFalse();
    assertThat(selector.proxyFor(URI.create("https://api.example.com/"))).isEmpty();
  }

  @Test
  void isProxiedAndProxyForTakeTheSameDecisionAsSelect() {
    assertThat(selector.isProxied("api.example.com")).isTrue();
    assertThat(selector.isProxied("wiki.intranet.example")).isFalse();
    assertThat(selector.isProxied("localhost")).isFalse();
    assertThat(selector.proxyFor(URI.create("https://s3.example.com:9000"))).contains(PROXY);
    assertThat(selector.proxyFor(URI.create("http://10.0.0.5:9000"))).isEmpty();
    assertThat(selector.proxyFor(URI.create("socket://s3.example.com:9000"))).isEmpty();
  }

  @Test
  void aNullUriIsRefusedAsTheContractDemands() {
    assertThatThrownBy(() -> selector.select(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
