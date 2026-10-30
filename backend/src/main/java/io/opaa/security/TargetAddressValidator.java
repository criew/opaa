package io.opaa.security;

import io.opaa.common.LogText;
import io.opaa.common.NumericAddress;
import io.opaa.outbound.OutboundProxySelector;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rejects a fetch target whose scheme is not {@code http}/{@code https}, or whose resolved
 * address(es) fall into a loopback, link-local ({@code 169.254.169.254} included), private or
 * otherwise non-routable range - IPv4 and IPv6 alike, CGNAT, reserved, benchmarking, unique local
 * and NAT64 included. Checked before the first request and again on every redirect hop. {@code
 * enabled} is the operator's off switch, {@code allowlist} the exception: an entry {@code host}
 * admits every port of the host, an entry {@code host:port} (IPv6 as {@code [addr]:port}) only that
 * port - of a URI, or of a bare host whose caller names the port it connects to.
 *
 * <p>A name that passes is pinned ({@link ResolutionPins}): a client of this JVM resolving it gets
 * the vetted addresses for a short while, so a name server cannot answer the check and the
 * connection differently. A {@link Scope#STORED_TARGET} check also guards the name until restart;
 * only an allowlist entry of this validator without a port ends that guard. Through the outbound
 * proxy nothing is pinned. A name outside ASCII is refused (ADR-0063).
 *
 * <p>A blocked range and a name that does not resolve are refused with the same German sentence
 * ({@link #refusal}), so whoever configures a target cannot probe which internal names exist; the
 * cause goes to the log only. The exception types still tell the two apart. The log names the host
 * and its address as {@link #namingHostsAs} decides - in the clear unless a caller says otherwise.
 *
 * <p>The target is checked, never the outbound proxy. A name that does not resolve locally passes
 * only when its connection goes through the outbound proxy, because the proxy resolves it: for
 * {@link #validate} when {@link OutboundProxySelector#proxyFor} routes the URI there, for {@link
 * #validateHost(String, boolean, Scope)} when the caller says so. {@link #validateHost(String,
 * Scope)} - SMB and the upload storage - stays strict. IP literals and resolvable names are checked
 * unchanged.
 */
public class TargetAddressValidator {

  private static final Logger log = LoggerFactory.getLogger(TargetAddressValidator.class);

  /**
   * Appended by callers to a rejection so whoever configures an internal source learns which
   * setting unblocks it; one constant, so every connector names the same variable.
   */
  public static final String ALLOWLIST_HINT =
      "Interne Adressen gibt der Betrieb über OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST frei.";

  private static final int NO_PORT = -1;

  private final boolean enabled;
  private final List<String> allowedHosts;
  private final int policy;
  private final Function<Object, String> loggedHosts;

  public TargetAddressValidator(boolean enabled, List<String> allowlist) {
    this.enabled = enabled;
    this.allowedHosts = allowlist == null ? List.of() : allowlist;
    this.policy = ResolutionPins.policyOf(this.allowedHosts);
    this.loggedHosts = LogText::of;
  }

  private TargetAddressValidator(TargetAddressValidator of, Function<Object, String> loggedHosts) {
    this.enabled = of.enabled;
    this.allowedHosts = of.allowedHosts;
    this.policy = of.policy;
    this.loggedHosts = loggedHosts;
  }

  /**
   * This validator, its log lines naming a refused host and its address through {@code names} - for
   * a caller whose target may come from content the log must not name, e.g. {@code LoggedName::of}
   * of a private library. Checks and user-facing messages stay the same.
   */
  public TargetAddressValidator namingHostsAs(Function<Object, String> names) {
    return new TargetAddressValidator(this, Objects.requireNonNull(names));
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
   * What a check protects (ADR-0063). Every caller names it; there is no default.
   *
   * <ul>
   *   <li>{@link #PER_CONNECTION}: a check right before the connection it vets - a redirect hop, a
   *       request, a crawl or feed fetch. Pinned for the connection only; every further connection
   *       is checked again.
   *   <li>{@link #STORED_TARGET}: a check of stored configuration a client later connects to
   *       without a check of its own - model endpoints, identity providers, the mail server. Also
   *       guarded until restart.
   * </ul>
   */
  public enum Scope {
    PER_CONNECTION,
    STORED_TARGET
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
  public void validate(URI uri, Scope scope) throws IOException {
    Objects.requireNonNull(scope, "scope");
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
    if (isAllowed(host, effectivePort(uri))) {
      releaseIfAdmittedOnEveryPort(host);
      return;
    }
    checkHost(host, OutboundProxySelector.instance().proxyFor(uri).isPresent(), scope);
  }

  /** The port a client connects to: the URI's own, or the default of its scheme. */
  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
  }

  /**
   * Validates a bare hostname with no scheme - an SMB server, a bucket host, the originals store -
   * with the same address-range check as {@link #validate}, strictly: a name that does not resolve
   * locally is rejected. A {@code null} host is a no-op.
   *
   * @throws TargetAddressBlockedException (an {@link IOException}) with a German, user-facing
   *     message when the host is rejected.
   */
  public void validateHost(String host, Scope scope) throws IOException {
    validateHost(host, false, scope);
  }

  /**
   * {@link #validateHost(String, Scope)} for a connection to {@code port}, so an allowlist entry
   * {@code host:port} admits it; a negative port is none.
   */
  public void validateHost(String host, int port, Scope scope) throws IOException {
    validateHost(host, port, false, scope);
  }

  /** {@link #validateHost(String, boolean, Scope)} for a connection to {@code port}. */
  public void validateHost(String host, int port, boolean throughOutboundProxy, Scope scope)
      throws IOException {
    Objects.requireNonNull(scope, "scope");
    if (!enabled || host == null) {
      return;
    }
    if (isAllowed(host, port < 0 ? NO_PORT : port)) {
      releaseIfAdmittedOnEveryPort(host);
      return;
    }
    checkHost(host, throughOutboundProxy, scope);
  }

  /**
   * Whether an allowlist entry names the host of {@code uri} on the port a client connects to - by
   * name alone, without resolving it and regardless of {@code enabled}.
   */
  public boolean isAllowlisted(URI uri) {
    String scheme = uri.getScheme();
    return uri.getHost() != null
        && scheme != null
        && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        && isAllowed(uri.getHost(), effectivePort(uri));
  }

  /** The port a client connects to for {@code uri}: its own, or the default of its scheme. */
  public static int portOf(URI uri) {
    return effectivePort(uri);
  }

  /**
   * {@link #validateHost(String, Scope)} for a client whose route the caller decided itself: with
   * {@code throughOutboundProxy} the outbound proxy resolves the name, so a name that does not
   * resolve locally passes. Only a caller that actually hands its client that proxy may say so.
   */
  public void validateHost(String host, boolean throughOutboundProxy, Scope scope)
      throws IOException {
    validateHost(host, NO_PORT, throughOutboundProxy, scope);
  }

  /**
   * Vets every address {@code host} resolves to and pins the name to them, guarding it for a stored
   * target; then vets what a client resolving the name now gets, which may still be an answer
   * cached before the pin.
   */
  private void checkHost(String host, boolean throughOutboundProxy, Scope scope)
      throws IOException {
    if (isLiteral(host)) {
      rejectBlocked(host, List.of(InetAddress.getAllByName(host)));
      return;
    }
    if (!host.chars().allMatch(c -> c <= 0x7F)) {
      throw new TargetAddressBlockedException(
          "Der Hostname "
              + host
              + " enthält Zeichen außerhalb von ASCII. Internationalisierte Namen bitte in"
              + " Punycode-Schreibweise (xn--…) angeben.");
    }
    ResolutionPins pins = ResolutionPins.process();
    ResolutionPins.Answer answer;
    try {
      answer = pins.resolveForCheck(host);
    } catch (UnknownHostException e) {
      if (throughOutboundProxy) {
        // The outbound proxy resolves the name, so a missing local resolution is no rejection.
        return;
      }
      throw unknownHost(host);
    }
    rejectBlocked(host, answer.addresses());
    if (throughOutboundProxy) {
      // The proxy resolves the name itself; neither a pin nor a guard would reach it.
      return;
    }
    if (scope == Scope.PER_CONNECTION) {
      if (!pins.pinConnection(host, answer)) {
        throw new GuardCapacityExceededException(
            "Die Zieladresse "
                + host
                + " kann derzeit nicht geprüft werden: In kurzer Zeit wurden zu viele verschiedene"
                + " Hostnamen geprüft. Bitte in einer Minute erneut versuchen.");
      }
    } else if (!pins.admit(host, policy, answer)) {
      throw new GuardCapacityExceededException(
          "Die Zieladresse "
              + host
              + " kann derzeit nicht geprüft werden: OPAA überwacht bereits die Höchstzahl"
              + " geprüfter Hostnamen. Ein Neustart des Backends setzt die Überwachung zurück.");
    }
    try {
      rejectBlocked(host, List.of(InetAddress.getAllByName(host)));
    } catch (UnknownHostException e) {
      throw unknownHost(host);
    }
  }

  /**
   * Ends this validator's guard of {@code host} when an entry admits it on every port; an entry
   * with a port does not, since a resolution knows no port.
   */
  private void releaseIfAdmittedOnEveryPort(String host) {
    if (isAllowed(host, NO_PORT)) {
      ResolutionPins.process().release(host, policy);
    }
  }

  /** An IP literal, bare or in brackets: it is its own address and never reaches DNS. */
  private static boolean isLiteral(String host) {
    boolean bracketed = host.length() > 2 && host.startsWith("[") && host.endsWith("]");
    return NumericAddress.isNumeric(bracketed ? host.substring(1, host.length() - 1) : host);
  }

  /**
   * A DNS failure, answered in the words of a blocked range ({@link #refusal}). Logged at INFO: a
   * dead link in crawled content is ordinary, unlike a target in a blocked range.
   */
  private UnknownTargetHostException unknownHost(String host) {
    log.info("Rejecting fetch to {} - the name does not resolve", loggedHosts.apply(host));
    return new UnknownTargetHostException(refusal(host));
  }

  /** The one user-facing sentence of a blocked range and of a name that does not resolve. */
  static String refusal(String host) {
    return "Die Zieladresse "
        + host
        + " ist nicht zugelassen oder nicht erreichbar: Der Name ist nicht auflösbar oder liegt in"
        + " einem gesperrten Adressbereich (lokal, privat oder nicht routbar).";
  }

  private void rejectBlocked(String host, List<InetAddress> addresses)
      throws TargetAddressBlockedException {
    for (InetAddress address : addresses) {
      if (isBlockedAddress(address)) {
        log.warn(
            "Rejecting fetch to {} - its resolved address lies in a blocked (loopback, link-local,"
                + " private or otherwise non-routable) range",
            loggedHosts.apply(host + " (" + address.getHostAddress() + ")"));
        throw new TargetAddressBlockedException(refusal(host));
      }
    }
  }

  /**
   * Whether an entry admits {@code host} on {@code port}; {@link #NO_PORT} matches no port entry.
   */
  private boolean isAllowed(String host, int port) {
    return allowedHosts.stream().anyMatch(entry -> admits(entry.trim(), host, port));
  }

  private static boolean admits(String entry, String host, int port) {
    int colon = portSeparator(entry);
    if (colon < 0) {
      return entry.equalsIgnoreCase(host);
    }
    int entryPort = parsePort(entry.substring(colon + 1));
    String name = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    return entryPort != NO_PORT
        && port == entryPort
        && entry.substring(0, colon).equalsIgnoreCase(name);
  }

  /** The colon before the port of an entry {@code host:port} or {@code [addr]:port}, else -1. */
  private static int portSeparator(String entry) {
    int colon = entry.lastIndexOf(':');
    boolean bracketed = entry.startsWith("[");
    boolean withPort =
        colon > 0
            && colon < entry.length() - 1
            && entry.substring(colon + 1).chars().allMatch(Character::isDigit)
            && (bracketed ? entry.charAt(colon - 1) == ']' : entry.indexOf(':') == colon);
    return withPort ? colon : -1;
  }

  /** The port 1-65535 of {@code digits}, else {@link #NO_PORT}; never throws. */
  private static int parsePort(String digits) {
    if (digits.length() > 5) {
      return NO_PORT;
    }
    int port = Integer.parseInt(digits);
    return port >= 1 && port <= 65535 ? port : NO_PORT;
  }

  /**
   * The entries of {@code allowlist} naming a port outside 1-65535; such an entry admits nothing,
   * so a caller refuses or reports it at start.
   */
  public static List<String> invalidEntries(List<String> allowlist) {
    if (allowlist == null) {
      return List.of();
    }
    return allowlist.stream()
        .map(String::trim)
        .filter(
            entry -> {
              int colon = portSeparator(entry);
              return colon >= 0 && parsePort(entry.substring(colon + 1)) == NO_PORT;
            })
        .toList();
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
   * IPv4 ranges none of {@link InetAddress}'s own predicates recognize: the reserved {@code
   * 240.0.0.0/4} block including the broadcast address, Carrier-Grade NAT ({@code 100.64.0.0/10}),
   * the IETF Protocol Assignments block ({@code 192.0.0.0/24}) and the benchmarking range ({@code
   * 198.18.0.0/15}). None are meant to be reachable from outside the assigning network.
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
   * Whether {@code bytes} (an IPv6 address) only carries an embedded IPv4 address - {@code
   * ::ffff:a.b.c.d} (mapped) or the deprecated {@code ::a.b.c.d} (compatible). Treating both alike
   * closes a gap a mapped-only check would leave: {@code ::7f00:1}, compatible-form {@code
   * 127.0.0.1}, is not recognized by {@link InetAddress#isLoopbackAddress()}.
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
  public static class TargetAddressBlockedException extends IOException {
    TargetAddressBlockedException(String message) {
      super(message);
    }
  }

  /**
   * A check of a new host name refused because this instance already guards the most names it
   * holds; a {@link TargetAddressBlockedException} for every existing caller.
   */
  public static final class GuardCapacityExceededException extends TargetAddressBlockedException {
    GuardCapacityExceededException(String message) {
      super(message);
    }
  }

  /**
   * The DNS branch of a rejection: the host resolved to nothing, so no address could be checked. A
   * {@link TargetAddressBlockedException} for every existing caller, with the same message; a
   * caller that handles it as transient rather than blocked can tell the two apart, but answers the
   * user alike.
   */
  public static final class UnknownTargetHostException extends TargetAddressBlockedException {
    UnknownTargetHostException(String message) {
      super(message);
    }
  }
}
