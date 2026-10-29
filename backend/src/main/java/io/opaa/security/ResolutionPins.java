package io.opaa.security;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The names the target address check admitted, each bound to the addresses it vetted (ADR-0063).
 * Every resolution in this JVM passes {@link #lookup} through {@link
 * PinnedAddressResolverProvider}: a bound name answers its vetted addresses for {@link
 * #PIN_LIFETIME} after the check, later a fresh answer that again holds no blocked address, and
 * never an address in a blocked range. A binding ends {@link #BINDING_LIFETIME} after the last
 * check of its name, or when an allowlist entry admits it. Unbound names are answered by the
 * upstream resolver unchanged. Held per instance (ADR-0053).
 */
final class ResolutionPins {

  /** How long a client resolving a just-checked name gets exactly the vetted addresses. */
  static final Duration PIN_LIFETIME = Duration.ofSeconds(60);

  /**
   * How long after its last check a name only ever resolves to addresses outside blocked ranges.
   */
  static final Duration BINDING_LIFETIME = Duration.ofHours(24);

  /** Above this many bindings an insertion first drops the expired ones. */
  private static final int PURGE_THRESHOLD = 4096;

  /** At most this many bindings; beyond it the binding with the oldest check gives way. */
  static final int MAX_BINDINGS = 16384;

  private static final LookupPolicy ALL_FAMILIES =
      LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6);

  private static final ResolutionPins PROCESS = new ResolutionPins(System::nanoTime);

  /** {@code addresses} is empty when the last fresh answer for the name held a blocked address. */
  private record Binding(List<InetAddress> addresses, long pinnedAt, long boundUntil) {}

  private final LongSupplier nanoTime;
  private final ConcurrentHashMap<String, Binding> bindings = new ConcurrentHashMap<>();
  private volatile InetAddressResolver builtin;
  private volatile InetAddressResolver replacement;

  ResolutionPins(LongSupplier nanoTime) {
    this.nanoTime = nanoTime;
  }

  /** The bindings every resolution of this JVM consults. */
  static ResolutionPins process() {
    return PROCESS;
  }

  /** Called once by the provider with the JDK's own resolver, which answers every unbound name. */
  void installBuiltin(InetAddressResolver resolver) {
    builtin = resolver;
  }

  /** Whether the provider is the resolver of this JVM, so a binding reaches every client. */
  boolean isInstalled() {
    return builtin != null;
  }

  /** Answers in place of the JDK's resolver until called with {@code null}; for tests only. */
  void replaceUpstream(InetAddressResolver resolver) {
    replacement = resolver;
  }

  InetAddressResolver upstream() {
    InetAddressResolver override = replacement;
    return override != null ? override : builtin;
  }

  /**
   * The addresses a check of {@code host} vets: the pinned ones while fresh, else a fresh answer of
   * the upstream resolver with every address family. Binds nothing; {@link #pin} does, after the
   * check.
   */
  List<InetAddress> resolveForCheck(String host) throws UnknownHostException {
    Binding binding = bindings.get(key(host));
    if (binding != null && isFresh(binding, nanoTime.getAsLong())) {
      return binding.addresses();
    }
    InetAddressResolver upstream = upstream();
    if (upstream == null) {
      return List.of(InetAddress.getAllByName(host));
    }
    return upstream.lookupByName(host, ALL_FAMILIES).toList();
  }

  /** Binds {@code host} to {@code vetted}, which the check found outside every blocked range. */
  void pin(String host, List<InetAddress> vetted) {
    long now = nanoTime.getAsLong();
    if (bindings.size() > PURGE_THRESHOLD) {
      bindings.values().removeIf(binding -> now - binding.boundUntil() > 0);
    }
    while (bindings.size() >= MAX_BINDINGS) {
      bindings.entrySet().stream()
          .min(Comparator.comparingLong(entry -> entry.getValue().boundUntil()))
          .ifPresent(oldest -> bindings.remove(oldest.getKey(), oldest.getValue()));
    }
    bindings.put(
        key(host), new Binding(List.copyOf(vetted), now, now + BINDING_LIFETIME.toNanos()));
  }

  /** Ends the binding of {@code host}: an allowlist entry admits it whatever it resolves to. */
  void release(String host) {
    bindings.remove(key(host));
  }

  /**
   * The resolution of {@code host} for any client of this JVM; see the class comment.
   *
   * @throws UnknownHostException also when a bound name now resolves into a blocked range
   */
  List<InetAddress> lookup(String host, LookupPolicy policy) throws UnknownHostException {
    InetAddressResolver upstream = upstream();
    String key = key(host);
    Binding binding = bindings.get(key);
    long now = nanoTime.getAsLong();
    if (binding != null && now - binding.boundUntil() > 0) {
      bindings.remove(key, binding);
      binding = null;
    }
    if (binding == null) {
      return upstream.lookupByName(host, policy).toList();
    }
    if (isFresh(binding, now)) {
      List<InetAddress> pinned = ordered(binding.addresses(), policy);
      if (!pinned.isEmpty()) {
        return named(host, pinned);
      }
    }
    List<InetAddress> fresh = upstream.lookupByName(host, policy).toList();
    boolean blocked = fresh.stream().anyMatch(TargetAddressValidator::isBlockedAddress);
    bindings.replace(
        key, binding, new Binding(blocked ? List.of() : fresh, now, binding.boundUntil()));
    if (blocked) {
      throw new UnknownHostException(
          host + ": resolves into a blocked address range since its target address check");
    }
    return fresh;
  }

  private static boolean isFresh(Binding binding, long now) {
    return !binding.addresses().isEmpty() && now - binding.pinnedAt() < PIN_LIFETIME.toNanos();
  }

  /**
   * {@code addresses} carrying {@code host} as their name, as the JDK's resolver answers: a client
   * takes the name for SNI and the certificate check from the address it connects to.
   */
  private static List<InetAddress> named(String host, List<InetAddress> addresses)
      throws UnknownHostException {
    InetAddress[] named = new InetAddress[addresses.size()];
    for (int i = 0; i < named.length; i++) {
      named[i] = InetAddress.getByAddress(host, addresses.get(i).getAddress());
    }
    return List.of(named);
  }

  /** The addresses of the families {@code policy} asks for, in the order it prefers. */
  private static List<InetAddress> ordered(List<InetAddress> addresses, LookupPolicy policy) {
    int characteristics = policy.characteristics();
    boolean ipv4 = (characteristics & LookupPolicy.IPV4) != 0;
    boolean ipv6 = (characteristics & LookupPolicy.IPV6) != 0;
    List<InetAddress> wanted =
        addresses.stream()
            .filter(address -> address instanceof Inet4Address ? ipv4 : ipv6)
            .toList();
    if ((characteristics & LookupPolicy.IPV4_FIRST) != 0) {
      return wanted.stream()
          .sorted(Comparator.comparing(address -> !(address instanceof Inet4Address)))
          .toList();
    }
    if ((characteristics & LookupPolicy.IPV6_FIRST) != 0) {
      return wanted.stream()
          .sorted(Comparator.comparing(address -> address instanceof Inet4Address))
          .toList();
    }
    return wanted;
  }

  /** One binding per name: case and a trailing root dot do not make another name. */
  private static String key(String host) {
    String lower = host.toLowerCase(Locale.ROOT);
    return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
  }
}
