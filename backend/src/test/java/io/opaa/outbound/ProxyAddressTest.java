package io.opaa.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProxyAddressTest {

  @Test
  void acceptsHttpUrlAndHostPort() {
    assertThat(ProxyAddress.parse("http://Proxy.Example.org:3128"))
        .contains(new ProxyAddress("proxy.example.org", 3128));
    assertThat(ProxyAddress.parse(" proxy.example.org:3128/ "))
        .contains(new ProxyAddress("proxy.example.org", 3128));
    assertThat(ProxyAddress.parse("http://[2001:db8::1]:8080"))
        .contains(new ProxyAddress("2001:db8::1", 8080));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://proxy.example.org:3128",
        "socks5://proxy.example.org:1080",
        "http://user:secret@proxy.example.org:3128",
        "http://proxy.example.org:3128/path",
        "http://proxy.example.org:3128?x=1",
        "http://proxy.example.org",
        "proxy.example.org",
        "proxy.example.org:0",
        "proxy.example.org:70000",
        "proxy example.org:3128",
        ""
      })
  void refusesEveryOtherForm(String value) {
    assertThat(ProxyAddress.parse(value)).isEqualTo(Optional.empty());
  }

  @Test
  void showsItselfAsHttpUrl() {
    assertThat(new ProxyAddress("proxy.example.org", 3128).toUrl())
        .isEqualTo("http://proxy.example.org:3128");
    assertThat(new ProxyAddress("2001:db8::1", 8080).toUrl())
        .isEqualTo("http://[2001:db8::1]:8080");
  }

  @Test
  void theSocketAddressStaysUnresolved() {
    assertThat(new ProxyAddress("proxy.example.org", 3128).socketAddress().isUnresolved()).isTrue();
  }
}
