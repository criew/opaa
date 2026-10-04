package io.opaa.indexing.source;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What the server address of a connection profile may be: an address with one of {@code schemes},
 * in order of preference, or exactly {@code fixed} - then no one is asked for one.
 */
public record ServerAddressRule(List<String> schemes, String fixed) {

  private static final ServerAddressRule WEB =
      new ServerAddressRule(List.of("https", "http"), null);

  public ServerAddressRule {
    Set<String> distinct = new LinkedHashSet<>();
    for (String scheme : schemes == null ? List.<String>of() : schemes) {
      distinct.add(Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT));
    }
    schemes = List.copyOf(distinct);
    if (fixed != null && fixed.isBlank()) {
      fixed = null;
    }
    if ((fixed == null) == schemes.isEmpty()) {
      throw new IllegalArgumentException("an address rule names schemes or a fixed address");
    }
  }

  /** An {@code https} or {@code http} address. */
  public static ServerAddressRule web() {
    return WEB;
  }

  /** An address with one of {@code schemes}. */
  public static ServerAddressRule schemes(String... schemes) {
    return new ServerAddressRule(List.of(schemes), null);
  }

  /** Exactly {@code address}. */
  public static ServerAddressRule fixed(String address) {
    return new ServerAddressRule(List.of(), Objects.requireNonNull(address, "address"));
  }

  public boolean isFixed() {
    return fixed != null;
  }
}
