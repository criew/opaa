package io.opaa.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * "Is this string an IP address literal?" - the one check every network restriction shares ({@link
 * TrustedProxyClientIpResolver}, the administrator network check of ADR-0033, the channel networks
 * of the external access). It is the guard in front of every matcher: a string that is no literal
 * must never reach {@code InetAddress.getByName} through a matcher, where it would be resolved
 * through DNS.
 */
public final class NumericAddress {

  private static final String IPV4_OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
  private static final Pattern IPV4 =
      Pattern.compile("^" + IPV4_OCTET + "(\\." + IPV4_OCTET + "){3}$");
  private static final Pattern IPV6_CHARACTERS = Pattern.compile("^[0-9a-fA-F:.]+$");

  private NumericAddress() {}

  /**
   * An IPv4 dotted quad or an IPv6 literal (hex groups and at least two colons, no zone, no port).
   * The IPv6 check parses the literal; a string with a colon never reaches DNS.
   */
  public static boolean isNumeric(String address) {
    if (address == null) {
      return false;
    }
    if (IPV4.matcher(address).matches()) {
      return true;
    }
    if (!IPV6_CHARACTERS.matcher(address).matches()
        || address.indexOf(':') == address.lastIndexOf(':')) {
      return false;
    }
    try {
      InetAddress.getByName(address);
      return true;
    } catch (UnknownHostException | IllegalArgumentException notALiteral) {
      return false;
    }
  }
}
