package io.opaa.outbound;

import io.opaa.common.NumericAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * The exclusion list of the outbound proxy in {@code NO_PROXY} style. A host name matches itself
 * and every subdomain ({@code .x} and {@code *.x} mean {@code x}); an IP literal matches itself; a
 * CIDR range matches only IP literals and never resolves a name. Case and a trailing dot do not
 * matter. {@code *}, ports and whitespace are refused by {@link #invalidEntries}.
 */
public final class ProxyExclusions {

  private static final Pattern HOST_NAME =
      Pattern.compile("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*$");

  private static final ProxyExclusions NONE = new ProxyExclusions(List.of());

  private final List<String> entries;
  private final List<String> hostNames;
  private final List<IpAddressMatcher> addresses;

  private ProxyExclusions(List<String> entries) {
    this.entries = List.copyOf(entries);
    List<String> names = new ArrayList<>();
    List<IpAddressMatcher> ranges = new ArrayList<>();
    for (String entry : this.entries) {
      if (isAddressEntry(entry)) {
        ranges.add(new IpAddressMatcher(entry));
      } else {
        names.add(entry);
      }
    }
    this.hostNames = List.copyOf(names);
    this.addresses = List.copyOf(ranges);
  }

  public static ProxyExclusions none() {
    return NONE;
  }

  /**
   * The list from already validated entries; invalid ones are dropped.
   *
   * @see #invalidEntries
   */
  public static ProxyExclusions of(List<String> entries) {
    List<String> valid = normalize(entries).stream().filter(ProxyExclusions::isValid).toList();
    return valid.isEmpty() ? NONE : new ProxyExclusions(valid);
  }

  /**
   * Trimmed, lower-cased, without a leading {@code .}/{@code *.} or a trailing dot, de-duplicated
   * in order; blank entries drop out. An entry with inner whitespace stays as it is, so {@link
   * #invalidEntries} can name it.
   */
  public static List<String> normalize(List<String> entries) {
    if (entries == null) {
      return List.of();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String entry : entries) {
      if (entry == null) {
        continue;
      }
      String value = stripBrackets(entry.strip().toLowerCase(Locale.ROOT));
      if (value.startsWith("*.")) {
        value = value.substring(2);
      } else if (value.startsWith(".")) {
        value = value.substring(1);
      }
      if (value.endsWith(".")) {
        value = value.substring(0, value.length() - 1);
      }
      if (!value.isEmpty()) {
        normalized.add(value);
      }
    }
    return List.copyOf(normalized);
  }

  /** The normalized entries that are neither a host name, an IP literal nor a CIDR range. */
  public static List<String> invalidEntries(List<String> entries) {
    return normalize(entries).stream().filter(entry -> !isValid(entry)).toList();
  }

  private static boolean isValid(String entry) {
    return isAddressEntry(entry) || (HOST_NAME.matcher(entry).matches() && !isAllDigits(entry));
  }

  /** An IP literal or a CIDR range of one - never anything DNS could resolve. */
  private static boolean isAddressEntry(String entry) {
    int slash = entry.indexOf('/');
    String address = stripBrackets(slash < 0 ? entry : entry.substring(0, slash));
    if (!NumericAddress.isNumeric(address)) {
      return false;
    }
    if (slash >= 0) {
      String bits = entry.substring(slash + 1);
      int max = address.contains(":") ? 128 : 32;
      if (!bits.matches("\\d{1,3}") || Integer.parseInt(bits) > max) {
        return false;
      }
    }
    try {
      new IpAddressMatcher(slash < 0 ? address : address + entry.substring(slash));
      return true;
    } catch (RuntimeException unparseable) {
      return false;
    }
  }

  /** A dotted name made of digits only would be a malformed IPv4 address, not a host. */
  private static boolean isAllDigits(String entry) {
    return entry.chars().allMatch(c -> c == '.' || Character.isDigit(c));
  }

  /** The entries as stored, normalized. */
  public List<String> entries() {
    return entries;
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** Whether {@code host} - a name or an IP literal, with or without brackets - is excluded. */
  public boolean matches(String host) {
    if (host == null || entries.isEmpty()) {
      return false;
    }
    String value = stripBrackets(host.strip().toLowerCase(Locale.ROOT));
    if (value.endsWith(".")) {
      value = value.substring(0, value.length() - 1);
    }
    if (value.isEmpty()) {
      return false;
    }
    if (NumericAddress.isNumeric(value)) {
      String literal = value;
      return addresses.stream().anyMatch(range -> range.matches(literal));
    }
    for (String name : hostNames) {
      if (value.equals(name) || value.endsWith("." + name)) {
        return true;
      }
    }
    return false;
  }

  static String stripBrackets(String host) {
    if (host.length() > 1 && host.startsWith("[") && host.endsWith("]")) {
      return host.substring(1, host.length() - 1);
    }
    return host;
  }
}
