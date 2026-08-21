package io.opaa.indexing;

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
 * cloud metadata address), private or otherwise non-routable range (#267) - both IPv4 and IPv6,
 * including an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) and IPv6 unique local addresses
 * ({@code fc00::/7}, not covered by {@link InetAddress#isSiteLocalAddress()}).
 *
 * <p><b>Checked before the first request of every crawl/RSS/attachment fetch and again on every
 * redirect hop</b> ({@link AutoindexCrawlerService#sendFollowingRedirects}, {@link
 * UrlFileDownloader#downloadBounded}, {@code RssFeedIndexingExecutor#sendDetailPageRequest}), so a
 * redirect chain - or a link a crawled directory listing or RSS feed itself carries, both of which
 * are content someone other than OPAA's own configuration controls - cannot walk a legitimately
 * public start address onto an internal one. {@link SourceConnectionTestService} in {@code
 * io.opaa.library} applies the identical check to the synchronous connection test, which otherwise
 * makes the same outbound request cheaper to reach than the asynchronous indexing run it mirrors.
 *
 * <p><b>Configurable, default active (#267 acceptance criteria).</b> {@link
 * IndexingProperties.TargetValidation#enabled()} is the operator's off switch for a deployment with
 * legitimate internal document sources; {@link IndexingProperties.TargetValidation#allowlist()}
 * lets specific hostnames stay reachable without disabling the check for every other target -
 * mirrors {@link FilesystemPathAllowlist}'s configuration style (an operator-controlled list,
 * empty/absent by default) for the URL-based quellentypen this class covers.
 *
 * <p><b>DNS-Rebinding (documented, accepted limitation - #267's own "Technische Hinweise").</b> The
 * address checked here and the address {@code HttpClient} eventually connects to both come from
 * resolving the same hostname, but not atomically - a second lookup between this check and the
 * actual connect (a resolver deliberately flipping answers, or a TTL expiring mid-chain) could in
 * principle still hand the connection to a different, unvetted address. {@code
 * java.net.http.HttpClient} (Java 21, this project's runtime - see ADR-0002) offers no supported
 * hook to pin a single request's connection to an address already resolved and vetted here, short
 * of maintaining a private connection pool this class does not otherwise need - closing this gap
 * completely is therefore not achievable on this HTTP client. This is hardening against
 * naheliegende Fehlgriffe and casual misuse, exactly the scope #267 itself states, not an airtight
 * guarantee.
 */
public class TargetAddressValidator {

  private static final Logger log = LoggerFactory.getLogger(TargetAddressValidator.class);

  private final boolean enabled;
  private final List<String> allowedHosts;

  public TargetAddressValidator(IndexingProperties.TargetValidation config) {
    this.enabled = config.enabled();
    this.allowedHosts = config.allowlist();
  }

  /**
   * A validator with checking turned off - used by tests exercising behaviour unrelated to target
   * validation, which otherwise reach only local test-server addresses this check would reject by
   * default.
   */
  public static TargetAddressValidator disabled() {
    return new TargetAddressValidator(new IndexingProperties.TargetValidation(false, List.of()));
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
    if (isAllowedHost(host)) {
      return;
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      // Deliberately not surfaced as a generic "unreachable" - a DNS failure and a resolved-but-
      // blocked target are different diagnoses for whoever configured this source.
      throw new TargetAddressBlockedException(
          "Der Host konnte nicht aufgelöst werden und wird abgelehnt: " + host);
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
    if (isIpv4MappedIpv6(bytes)) {
      byte[] embedded = new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
      return isBlockedIpv4Bytes(embedded);
    }
    if (isUniqueLocalIpv6(bytes)) {
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
   * genuine {@link Inet4Address} and for the IPv4 address embedded in an IPv4-mapped IPv6 one,
   * since {@link InetAddress}'s own {@code isXxxAddress} predicates only recognize a real {@link
   * Inet4Address} instance, not an IPv6-typed address that merely carries an IPv4 payload.
   */
  private static boolean isBlockedIpv4Bytes(byte[] ipv4Bytes) {
    try {
      return isCommonBlockedRange(InetAddress.getByAddress(ipv4Bytes));
    } catch (UnknownHostException e) {
      // getByAddress only validates the byte array's length (4 or 16) - never thrown for an
      // already 4-byte array. Treated as blocked regardless, consistent with every other
      // unparsable-input branch in this class.
      return true;
    }
  }

  /** {@code ::ffff:0:0/96} - an IPv6 address that only carries an embedded IPv4 address. */
  private static boolean isIpv4MappedIpv6(byte[] bytes) {
    if (bytes.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
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
   * Thrown by {@link #validate} when the target is rejected - message is German and user-facing.
   */
  public static final class TargetAddressBlockedException extends IOException {
    TargetAddressBlockedException(String message) {
      super(message);
    }
  }
}
