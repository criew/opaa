package io.opaa.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProxyExclusionsTest {

  @Test
  void aHostNameMatchesItselfAndEverySubdomain() {
    ProxyExclusions exclusions = ProxyExclusions.of(List.of("intranet.example"));

    assertThat(exclusions.matches("intranet.example")).isTrue();
    assertThat(exclusions.matches("wiki.intranet.example")).isTrue();
    assertThat(exclusions.matches("a.b.intranet.example")).isTrue();
    assertThat(exclusions.matches("otherintranet.example")).isFalse();
    assertThat(exclusions.matches("intranet.example.org")).isFalse();
  }

  @Test
  void aLeadingDotOrWildcardMeansTheSameAsTheName() {
    assertThat(ProxyExclusions.normalize(List.of(".corp.example", "*.lan.example")))
        .containsExactly("corp.example", "lan.example");
    ProxyExclusions exclusions = ProxyExclusions.of(List.of(".corp.example", "*.lan.example"));

    assertThat(exclusions.matches("corp.example")).isTrue();
    assertThat(exclusions.matches("git.corp.example")).isTrue();
    assertThat(exclusions.matches("lan.example")).isTrue();
    assertThat(exclusions.matches("nas.lan.example")).isTrue();
  }

  @Test
  void caseAndATrailingDotDoNotMatter() {
    ProxyExclusions exclusions = ProxyExclusions.of(List.of("  Intranet.Example.  "));

    assertThat(exclusions.entries()).containsExactly("intranet.example");
    assertThat(exclusions.matches("WIKI.INTRANET.EXAMPLE.")).isTrue();
  }

  @Test
  void anIpLiteralMatchesOnlyItself() {
    ProxyExclusions exclusions = ProxyExclusions.of(List.of("192.0.2.10", "2001:db8::1"));

    assertThat(exclusions.matches("192.0.2.10")).isTrue();
    assertThat(exclusions.matches("192.0.2.11")).isFalse();
    assertThat(exclusions.matches("[2001:db8::1]")).isTrue();
    assertThat(exclusions.matches("2001:db8::2")).isFalse();
  }

  @Test
  void aCidrRangeMatchesIpLiteralsInsideIt() {
    ProxyExclusions exclusions = ProxyExclusions.of(List.of("10.0.0.0/8", "fd00::/8"));

    assertThat(exclusions.matches("10.20.30.40")).isTrue();
    assertThat(exclusions.matches("11.0.0.1")).isFalse();
    assertThat(exclusions.matches("[fd12::1]")).isTrue();
  }

  @Test
  void aCidrRangeNeverResolvesAName() {
    ProxyExclusions exclusions = ProxyExclusions.of(List.of("127.0.0.0/8"));

    // "localhost" resolves to 127.0.0.1 - a range matching it would have asked DNS.
    assertThat(exclusions.matches("localhost")).isFalse();
  }

  @Test
  void refusesWildcardPortsWhitespaceAndMalformedEntries() {
    assertThat(
            ProxyExclusions.invalidEntries(
                List.of(
                    "*",
                    "intranet.example:8080",
                    "intra net.example",
                    "10.0.0.0/33",
                    "300.1.1.1",
                    "-bad.example",
                    "ok.example",
                    "10.0.0.0/8")))
        .containsExactly(
            "*",
            "intranet.example:8080",
            "intra net.example",
            "10.0.0.0/33",
            "300.1.1.1",
            "-bad.example");
  }

  @Test
  void blankAndDuplicateEntriesDropOut() {
    assertThat(ProxyExclusions.normalize(Arrays.asList("a.example", " ", null, "A.example")))
        .containsExactly("a.example");
  }

  @Test
  void anEmptyListMatchesNothing() {
    assertThat(ProxyExclusions.none().matches("anything.example")).isFalse();
    assertThat(ProxyExclusions.of(List.of()).isEmpty()).isTrue();
  }
}
