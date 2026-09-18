package io.opaa.externalaccess;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Normalisation, syntax check and matching of the channel's CIDR list. A host name is refused
 * before {@link IpAddressMatcher} sees it, on both sides: in a stored range it would be resolved
 * through DNS at match time, and a client address taken from a proxy header could otherwise be
 * attacker chosen (same reasoning as {@code LocalAdminNetworkPolicy}).
 */
final class CidrList {

  static final String SEPARATOR = ",";

  private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
  private static final Pattern IPV4 = Pattern.compile("^" + OCTET + "(\\." + OCTET + "){3}$");
  private static final Pattern IPV6 =
      Pattern.compile("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(%[A-Za-z0-9]+)?$");

  private CidrList() {}

  /** Trimmed, lower-cased, de-duplicated, order preserved; blank entries drop out. */
  static List<String> normalize(List<String> cidrs) {
    if (cidrs == null) {
      return List.of();
    }
    return cidrs.stream()
        .filter(cidr -> cidr != null)
        .map(cidr -> cidr.trim().toLowerCase(Locale.ROOT))
        .filter(cidr -> !cidr.isEmpty())
        .distinct()
        .toList();
  }

  static List<String> parseColumn(String column) {
    if (column == null || column.isBlank()) {
      return List.of();
    }
    return normalize(Arrays.asList(column.split(SEPARATOR)));
  }

  /** A single address or a range, IPv4 or IPv6 - never anything DNS could resolve. */
  static boolean isValid(String cidr) {
    if (cidr == null || cidr.isBlank()) {
      return false;
    }
    String value = cidr.trim();
    int slash = value.indexOf('/');
    String address = slash < 0 ? value : value.substring(0, slash);
    if (!isNumericAddress(address)) {
      return false;
    }
    if (slash >= 0) {
      String bits = value.substring(slash + 1);
      if (!bits.matches("\\d{1,3}")) {
        return false;
      }
    }
    try {
      new IpAddressMatcher(value);
      return true;
    } catch (RuntimeException unparseable) {
      return false;
    }
  }

  static boolean isNumericAddress(String address) {
    return IPV4.matcher(address).matches() || IPV6.matcher(address).matches();
  }

  /** Whether {@code remoteAddress} lies in one of the ranges; an empty list allows nobody. */
  static boolean matches(List<IpAddressMatcher> ranges, String remoteAddress) {
    if (ranges.isEmpty() || remoteAddress == null) {
      return false;
    }
    String address = remoteAddress.trim();
    if (!isNumericAddress(address)) {
      return false;
    }
    try {
      return ranges.stream().anyMatch(range -> range.matches(address));
    } catch (RuntimeException unparseable) {
      return false;
    }
  }

  static List<IpAddressMatcher> matchers(List<String> cidrs) {
    return cidrs.stream().filter(CidrList::isValid).map(IpAddressMatcher::new).toList();
  }
}
