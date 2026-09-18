package io.opaa.externalaccess;

import io.opaa.security.NumericAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Normalisation, syntax check and matching of the channel's CIDR list. A host name is refused
 * before {@link IpAddressMatcher} sees it, on both sides: in a stored range it would be resolved
 * through DNS at match time, and a client address that is no literal would be too.
 */
final class CidrList {

  static final String SEPARATOR = ",";

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
    if (!NumericAddress.isNumeric(address)) {
      return false;
    }
    if (slash >= 0 && !value.substring(slash + 1).matches("\\d{1,3}")) {
      return false;
    }
    try {
      new IpAddressMatcher(value);
      return true;
    } catch (RuntimeException unparseable) {
      return false;
    }
  }

  /** Whether {@code remoteAddress} lies in one of the ranges; an empty list allows nobody. */
  static boolean matches(List<IpAddressMatcher> ranges, String remoteAddress) {
    if (ranges.isEmpty() || remoteAddress == null) {
      return false;
    }
    String address = remoteAddress.trim();
    if (!NumericAddress.isNumeric(address)) {
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
