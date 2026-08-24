package io.opaa.sourceaccess;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejects a fetch target whose scheme is not {@code http}/{@code https}, or whose resolved IP
 * address(es) fall inside a loopback, link-local (including {@code 169.254.169.254}, the common
 * cloud metadata address), private or otherwise non-routable range - both IPv4 and IPv6, including
 * ranges no plain {@link InetAddress} predicate recognizes on its own: Carrier-Grade NAT ({@code
 * 100.64.0.0/10}), the reserved block and broadcast address ({@code 240.0.0.0/4}, {@code
 * 255.255.255.255}), IETF Protocol Assignments ({@code 192.0.0.0/24}), benchmarking ({@code
 * 198.18.0.0/15}), IPv6 unique local addresses ({@code fc00::/7}, not covered by {@link
 * InetAddress#isSiteLocalAddress()}), the IPv6 NAT64 prefix ({@code 64:ff9b::/96}) and both
 * IPv4-embedded IPv6 forms - {@code ::ffff:a.b.c.d} (mapped) and the deprecated {@code ::a.b.c.d}
 * (compatible) - checked against the embedded IPv4 address.
 *
 * <p>Checked before the first request of every crawl/RSS/attachment fetch and again on every
 * redirect hop ({@link RedirectFollowingFetcher#sendFollowingRedirects}, {@link
 * BoundedDownloader#downloadBounded}), so a redirect chain - or a link a crawled directory listing
 * or RSS feed itself carries - cannot walk a legitimately public start address onto an internal
 * one. {@code io.opaa.library.SourceConnectionTestService} applies the identical check to the
 * synchronous connection test.
 *
 * <p>Configurable, default active: {@code enabled} is the operator's off switch for a deployment
 * with legitimate internal document sources; {@code allowlist} lets specific hostnames stay
 * reachable without disabling the check for every other target. Both are supplied by the caller
 * (bound from {@code opaa.indexing.target-validation} in {@code
 * io.opaa.indexing.IndexingProperties} - this class deliberately takes primitives rather than that
 * configuration type, so this package never depends on {@code io.opaa.indexing}).
 *
 * <p>DNS rebinding is a documented, accepted limitation: the address checked here and the address
 * {@code HttpClient} eventually connects to both come from resolving the same hostname, but not
 * atomically. {@code java.net.http.HttpClient} offers no supported hook to pin a single request's
 * connection to an address already resolved and vetted here - closing this gap completely is
 * therefore not achievable on this HTTP client. This is hardening against naheliegende Fehlgriffe
 * and casual misuse, not an airtight guarantee.
 */
public class TargetAddressValidator {

  private static final Logger log = LoggerFactory.getLogger(TargetAddressValidator.class);

  private final boolean enabled;
  private final List<String> allowedHosts;

  public TargetAddressValidator(boolean enabled, List<String> allowlist) {
    this.enabled = enabled;
    this.allowedHosts = allowlist == null ? List.of() : allowlist;
  }

  /**
   * A validator with checking turned off - used by tests exercising behaviour unrelated to target
   * validation, which otherwise reach only local test-server addresses this check would reject by
   * default.
   */
  public static TargetAddressValidator disabled() {
    return new TargetAddressValidator(false, List.of());
  }

  /**
   * Validates {@code uri} as a fetch target: scheme must be {@code http}/{@code https}, and - when
   * enabled and the host is not on the allowlist - every address the host resolves to must lie
   * outside every blocked range. A no-op when disabled via configuration.
   *
   * @throws TargetAddressBlockedException (an {@link IOException}) with a German, user-facing
   *     message when the target is rejected - propagates through every caller's existing {@code
   *     throws IOException} the same way any other connection failure already does.
   */
  public void validate(URI uri) throws IOException {
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw new TargetAddressBlockedException(
          "Nur die Schemata http und https werden unterstützt, abgelehntes Schema: " + scheme);
    }
    if (!enabled) {
      return;
    }
    String host = uri.getHost();
    if (host == null) {
      throw new TargetAddressBlockedException("Die Zieladresse enthält keinen gültigen Host.");
    }
    checkHost(host);
  }

  /**
   * Validates a bare hostname with no scheme: an HTTP(S) proxy's own address (from {@code
   * sourceProxy}) is exactly as caller-controlled as the target URL itself and determines where the
   * TCP connection - and any credentials sent over it - actually goes, but carries no scheme of its
   * own to run {@link #validate}'s scheme check against. Applies the identical address-range check
   * {@link #validate} applies to a URI's host; a no-op when {@code host} is {@code null} (no proxy
   * configured - unlike {@link #validate}, a missing proxy host is not itself an error) or checking
   * is disabled.
   *
   * @throws TargetAddressBlockedException (an {@link IOException}) with a German, user-facing
   *     message when the host is rejected.
   */
  public void validateHost(String host) throws IOException {
    if (!enabled || host == null) {
      return;
    }
    checkHost(host);
  }

  private void checkHost(String host) throws IOException {
    if (isAllowedHost(host)) {
      return;
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      // Deliberately not surfaced as a generic "unreachable" - a DNS failure and a resolved-but-
      // blocked target are different diagnoses for whoever configured this source. Same wording
      // SourceConnectionTestService#translateConnectionError already used for an ordinary
      // UnknownHostException.
      throw new TargetAddressBlockedException(
          "Der Host konnte nicht gefunden werden (DNS-Auflösung fehlgeschlagen): " + host);
    }
    for (InetAddress address : addresses) {
      if (isBlockedAddress(address)) {
        log.warn(
            "Rejecting fetch to {} - resolved address {} lies in a blocked (loopback, link-local,"
                + " private or otherwise non-routable) range",
            host,
            address.getHostAddress());
        throw new TargetAddressBlockedException(
            "Die Zieladresse "
                + host
                + " liegt in einem gesperrten Adressbereich (lokal, privat oder nicht routbar) und"
                + " wird aus Sicherheitsgründen abgelehnt.");
      }
    }
  }

  private boolean isAllowedHost(String host) {
    return allowedHosts.stream().anyMatch(allowed -> allowed.equalsIgnoreCase(host));
  }

  /** Package-private so {@code TargetAddressValidatorTest} can exercise it directly, per range. */
  static boolean isBlockedAddress(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      return isBlockedIpv4Bytes(bytes) || isCommonBlockedRange(address);
    }
    // IPv6 (16 bytes).
    if (isIpv4EmbeddedIpv6(bytes)) {
      // isCommonBlockedRange(address) too, not the embedded check alone: the deprecated
      // IPv4-compatible form (unlike the mapped one) overlaps with addresses Java already
      // recognizes directly on the IPv6 side - ::1 (loopback) is bytes[10..11] == 0 the same way
      // an IPv4-compatible address is, but its embedded "IPv4" (0.0.0.1) is not itself in any
      // blocked IPv4 range. Without this fallback, ::1 fell through this branch as allowed.
      byte[] embedded = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
      return isBlockedIpv4Bytes(embedded) || isCommonBlockedRange(address);
    }
    if (isUniqueLocalIpv6(bytes) || isNat64Ipv6(bytes)) {
      return true;
    }
    return isCommonBlockedRange(address);
  }

  /** Ranges {@link InetAddress} already recognizes correctly for both IPv4 and IPv6. */
  private static boolean isCommonBlockedRange(InetAddress address) {
    return address.isLoopbackAddress()
        || address.isAnyLocalAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress();
  }

  /**
   * Whether {@code ipv4Bytes} (4 bytes, big-endian) falls in a blocked range - used both for a
   * genuine {@link Inet4Address} and for the IPv4 address embedded in an IPv4-mapped/-compatible
   * IPv6 one, since {@link InetAddress}'s own {@code isXxxAddress} predicates only recognize a real
   * {@link Inet4Address} instance, not an IPv6-typed address that merely carries an IPv4 payload.
   */
  private static boolean isBlockedIpv4Bytes(byte[] ipv4Bytes) {
    if (isAdditionalBlockedIpv4Range(ipv4Bytes)) {
      return true;
    }
    try {
      return isCommonBlockedRange(InetAddress.getByAddress(ipv4Bytes));
    } catch (UnknownHostException e) {
      // getByAddress only validates the byte array's length (4 or 16) - never thrown for an
      // already 4-byte array. Treated as blocked regardless, consistent with every other
      // unparsable-input branch in this class.
      return true;
    }
  }

  /**
   * IPv4 ranges none of {@link InetAddress}'s own {@code isXxxAddress} predicates recognize at all:
   * the reserved {@code 240.0.0.0/4} block - which also covers the broadcast address {@code
   * 255.255.255.255}, neither multicast nor "any local" to {@link InetAddress} - Carrier-Grade NAT
   * ({@code 100.64.0.0/10}, RFC 6598), the IETF Protocol Assignments block ({@code 192.0.0.0/24})
   * and the benchmarking range ({@code 198.18.0.0/15}, RFC 2544). None of these are meant to be
   * reachable from outside the network that assigned them.
   */
  private static boolean isAdditionalBlockedIpv4Range(byte[] ipv4Bytes) {
    int first = ipv4Bytes[0] & 0xFF;
    int second = ipv4Bytes[1] & 0xFF;
    if (first >= 240) {
      return true; // 240.0.0.0/4, including 255.255.255.255
    }
    if (first == 100 && second >= 64 && second <= 127) {
      return true; // 100.64.0.0/10
    }
    if (first == 192 && second == 0 && (ipv4Bytes[2] & 0xFF) == 0) {
      return true; // 192.0.0.0/24
    }
    return first == 198 && (second == 18 || second == 19); // 198.18.0.0/15
  }

  /**
   * Whether {@code bytes} (16 bytes, an IPv6 address) only carries an embedded IPv4 address -
   * either {@code ::ffff:a.b.c.d} (IPv4-mapped, RFC 4291) or the older, deprecated {@code
   * ::a.b.c.d} (IPv4-compatible) - both {@code ::/96} prefixes that differ only in the two bytes
   * right before the embedded address. Treating both alike closes a gap the mapped-only check would
   * leave: {@code ::7f00:1} (IPv4-compatible for {@code 127.0.0.1}) is not itself recognized as
   * loopback by {@link InetAddress#isLoopbackAddress()} - only literal {@code ::1} is.
   */
  private static boolean isIpv4EmbeddedIpv6(byte[] bytes) {
    if (bytes.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    boolean compatible = bytes[10] == 0 && bytes[11] == 0;
    return mapped || compatible;
  }

  /**
   * {@code fc00::/7} - IPv6 unique local addresses (RFC 4193), the IPv6 counterpart to IPv4's
   * private ranges. Not recognized by {@link InetAddress#isSiteLocalAddress()}, which only
   * implements the older, deprecated {@code fec0::/10} site-local range for IPv6.
   */
  private static boolean isUniqueLocalIpv6(byte[] bytes) {
    return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
  }

  /**
   * {@code 64:ff9b::/96} - the well-known NAT64 prefix (RFC 6052), which translates an embedded
   * IPv4 address on the other side of the NAT64 gateway; not recognized by any {@link InetAddress}
   * predicate. Deliberately not folded into {@link #isIpv4EmbeddedIpv6} - this class has no reason
   * to inspect the embedded address itself, the whole prefix is non-routable regardless.
   */
  private static boolean isNat64Ipv6(byte[] bytes) {
    if (bytes.length != 16) {
      return false;
    }
    if (bytes[0] != 0x00
        || (bytes[1] & 0xFF) != 0x64
        || (bytes[2] & 0xFF) != 0xFF
        || (bytes[3] & 0xFF) != 0x9B) {
      return false;
    }
    for (int i = 4; i < 12; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Thrown by {@link #validate} when the target is rejected - message is German and user-facing.
   */
  public static final class TargetAddressBlockedException extends IOException {
    TargetAddressBlockedException(String message) {
      super(message);
    }
  }
}
