package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of {@link TargetAddressValidator} (#267): the scheme check, every blocked address
 * range (IPv4 and IPv6, including the IPv4-mapped and unique-local IPv6 cases the plain {@link
 * InetAddress} predicates alone do not cover), the allowlist bypass and the disabled/enabled
 * switch. Reproduces the two acceptance-criteria scenarios directly: a loopback/private target is
 * rejected while enabled, and disabling the check turns the identical target into a no-op.
 */
class TargetAddressValidatorTest {

  private TargetAddressValidator enabled(List<String> allowlist) {
    return new TargetAddressValidator(new IndexingProperties.TargetValidation(true, allowlist));
  }

  private TargetAddressValidator enabled() {
    return enabled(List.of());
  }

  // --- scheme ---------------------------------------------------------

  @Test
  void rejectsAFtpUrlWithAGermanMessage() {
    assertThatThrownBy(() -> enabled().validate(URI.create("ftp://example.com/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class)
        .hasMessageContaining("http")
        .hasMessageContaining("https");
  }

  @Test
  void schemeIsCheckedEvenWhenDisabled() {
    // The scheme check is not part of the "off switch" - only the address check is (see
    // TargetAddressValidator's own Javadoc): OPAA never speaks anything but http(s) regardless of
    // whether an operator has disabled the SSRF address check for their own network.
    TargetAddressValidator disabled = TargetAddressValidator.disabled();
    assertThatThrownBy(() -> disabled.validate(URI.create("ftp://example.com/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
  }

  @Test
  void allowsHttpsWhenDisabled() throws Exception {
    assertThatCode(
            () -> TargetAddressValidator.disabled().validate(URI.create("https://127.0.0.1/")))
        .doesNotThrowAnyException();
  }

  // --- disabled switch (acceptance criterion: konfigurierbar) --------

  @Test
  void loopbackIsRejectedWhenEnabledButAllowedWhenDisabled() {
    URI loopback = URI.create("http://127.0.0.1/");
    assertThatThrownBy(() -> enabled().validate(loopback))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class)
        .hasMessageContaining("gesperrten Adressbereich");
    assertThatCode(() -> TargetAddressValidator.disabled().validate(loopback))
        .doesNotThrowAnyException();
  }

  // --- IPv4 blocked ranges --------------------------------------------

  @Test
  void rejectsIpv4Loopback() {
    assertThatThrownBy(() -> enabled().validate(URI.create("http://127.0.0.1/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
  }

  @Test
  void rejectsIpv4PrivateTenSlashEight() {
    assertThatThrownBy(() -> enabled().validate(URI.create("http://10.0.0.5/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
  }

  @Test
  void rejectsIpv4Private172Range() {
    assertThatThrownBy(() -> enabled().validate(URI.create("http://172.16.0.5/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
  }

  @Test
  void rejectsIpv4Private192168Range() {
    assertThatThrownBy(() -> enabled().validate(URI.create("http://192.168.1.5/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
  }

  @Test
  void rejectsIpv4LinkLocalIncludingCloudMetadata() {
    // 169.254.169.254 - the common cloud metadata endpoint (#267 acceptance criteria).
    assertThatThrownBy(() -> enabled().validate(URI.create("http://169.254.169.254/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
  }

  @Test
  void allowsAGenuinelyPublicIpv4Address() throws Exception {
    // 9.9.9.9 (Quad9 public resolver) - a real, routable public address, never touched by this
    // test (only isBlockedAddress's own classification is exercised, not a live connection).
    assertThatCode(() -> assertNotBlocked("9.9.9.9")).doesNotThrowAnyException();
  }

  // --- IPv6 blocked ranges ---------------------------------------------

  @Test
  void rejectsIpv6Loopback() {
    assertThatThrownBy(() -> enabled().validate(URI.create("http://[::1]/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
  }

  @Test
  void rejectsIpv6LinkLocal() {
    assertThatThrownBy(() -> enabled().validate(URI.create("http://[fe80::1]/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
  }

  @Test
  void rejectsIpv6UniqueLocal() throws Exception {
    // fc00::/7 (RFC 4193) - not recognized by InetAddress#isSiteLocalAddress at all (see
    // TargetAddressValidator's own Javadoc), the one range this class must classify itself.
    assertBlocked("fd00::1");
    assertBlocked("fc00::1");
  }

  @Test
  void rejectsIpv4MappedIpv6LoopbackAndPrivateAddresses() throws Exception {
    // ::ffff:127.0.0.1 and ::ffff:10.0.0.1 - an IPv6-typed address that only carries an embedded
    // IPv4 payload, which the plain Inet4Address predicates never see at all.
    assertBlocked("::ffff:127.0.0.1");
    assertBlocked("::ffff:10.0.0.1");
  }

  @Test
  void allowsAGenuinelyPublicIpv6Address() throws Exception {
    // 2620:fe::fe (Quad9's public IPv6 resolver).
    assertNotBlocked("2620:fe::fe");
  }

  private static void assertBlocked(String literal) throws Exception {
    org.assertj.core.api.Assertions.assertThat(
            TargetAddressValidator.isBlockedAddress(InetAddress.getByName(literal)))
        .as("expected %s to be blocked", literal)
        .isTrue();
  }

  private static void assertNotBlocked(String literal) throws Exception {
    org.assertj.core.api.Assertions.assertThat(
            TargetAddressValidator.isBlockedAddress(InetAddress.getByName(literal)))
        .as("expected %s to be allowed", literal)
        .isFalse();
  }

  // --- allowlist --------------------------------------------------------

  @Test
  void allowlistedHostnameBypassesTheAddressCheckEvenIfItResolvesToALoopbackAddress() {
    TargetAddressValidator validator = enabled(List.of("internal-source.local"));
    // Resolves to 127.0.0.1 via the test JVM's own hosts handling of "localhost"-style names is
    // not guaranteed portable - instead this asserts the allowlist is checked before resolution is
    // even attempted, by using a hostname that would otherwise fail DNS resolution outright (and
    // therefore be rejected as unresolvable, not merely as blocked) if the allowlist did not short-
    // circuit first.
    assertThatCode(() -> validator.validate(URI.create("http://internal-source.local/")))
        .doesNotThrowAnyException();
  }

  @Test
  void allowlistIsCaseInsensitive() {
    TargetAddressValidator validator = enabled(List.of("Internal-Source.Local"));
    assertThatCode(() -> validator.validate(URI.create("http://internal-source.local/")))
        .doesNotThrowAnyException();
  }

  @Test
  void allowlistDoesNotExemptAnUnrelatedHost() {
    TargetAddressValidator validator = enabled(List.of("internal-source.local"));
    assertThatThrownBy(() -> validator.validate(URI.create("http://127.0.0.1/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class);
  }

  // --- unresolvable host --------------------------------------------------

  @Test
  void rejectsAHostThatCannotBeResolved() {
    assertThatThrownBy(
            () ->
                enabled()
                    .validate(URI.create("http://this-host-does-not-exist.invalid.opaa-test/")))
        .isInstanceOf(TargetAddressValidator.TargetAddressBlockedException.class)
        .hasMessageContaining("aufgelöst");
  }
}
