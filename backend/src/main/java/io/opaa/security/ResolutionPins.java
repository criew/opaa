package io.opaa.security;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The names the target address check admitted (ADR-0063). Every resolution in this JVM passes
 * {@link #lookup} through {@link PinnedAddressResolverProvider}. For {@link #PIN_LIFETIME} after a
 * check a name answers exactly the addresses it vetted: a check before a connection pins it in a
 * bounded table that drops its oldest entry, a check of stored configuration also guards it until
 * restart, so afterwards every fresh answer loses its blocked addresses. Only an allowlist entry of
 * the guarding policy ends a guard; a full guard table refuses new names instead of dropping one.
 * Other names pass unchanged. Held per instance (ADR-0053).
 */
final class ResolutionPins {

  /** How long a client resolving a just-checked name gets exactly the vetted addresses. */
  static final Duration PIN_LIFETIME = Duration.ofSeconds(60);

  /**
   * A check reuses an upstream answer for the same name this young, so a burst of checks costs one
   * query; a change of the name still arrives within it.
   */
  static final Duration ANSWER_REUSE = Duration.ofSeconds(5);

  /** At most this many guarded names; a check of a further name is refused. */
  static final int MAX_GUARDED_NAMES = 100_000;

  /**
   * At most this many pins of checks before a connection; only an expired pin gives way, a check of
   * a new name finding the table full of pins in force is refused.
   */
  static final int MAX_CONNECTION_PINS = 50_000;

  private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

  private static final LookupPolicy ALL_FAMILIES =
      LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6);

  private static final Logger log = LoggerFactory.getLogger(ResolutionPins.class);

  private static final ResolutionPins PROCESS =
      new ResolutionPins(System::nanoTime, MAX_GUARDED_NAMES, MAX_CONNECTION_PINS);

  private static final ConcurrentHashMap<String, Integer> POLICIES = new ConcurrentHashMap<>();
  private static final AtomicInteger NEXT_POLICY = new AtomicInteger();

  /** The first 128 bits of the SHA-256 of a normalised ASCII name: compact and fixed-size. */
  record NameKey(long high, long low) {}

  /** An upstream answer for a name and when it was fetched. */
  record Answer(List<InetAddress> addresses, long fetchedAt) {}

  /**
   * A guarded name: its latest vetted addresses ({@code null} once the pin expired) and the
   * policies that guard it.
   */
  private record Guard(List<InetAddress> pinned, long pinnedAt, int[] policies) {}

  private final LongSupplier nanoTime;
  private final int capacity;
  private final ConcurrentHashMap<NameKey, Guard> guards = new ConcurrentHashMap<>();
  private final Map<NameKey, Answer> connectionPins;
  private final int connectionCapacity;
  private final AtomicLong refusals = new AtomicLong();
  private final AtomicLong refusedConnectionChecks = new AtomicLong();
  private final AtomicLong lastFullWarning = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastFilterWarning = new AtomicLong(Long.MIN_VALUE);
  private volatile InetAddressResolver builtin;
  private volatile InetAddressResolver replacement;

  ResolutionPins(LongSupplier nanoTime, int capacity, int connectionCapacity) {
    this.nanoTime = nanoTime;
    this.capacity = capacity;
    this.connectionCapacity = connectionCapacity;
    this.connectionPins = Collections.synchronizedMap(new LinkedHashMap<>());
  }

  /** The guards every resolution of this JVM consults. */
  static ResolutionPins process() {
    return PROCESS;
  }

  /**
   * One id per distinct allowlist, so a release of one policy never ends the guard of another.
   * Entries are compared trimmed and case-insensitively, in any order.
   */
  static int policyOf(Collection<String> allowlist) {
    String normalised =
        allowlist.stream()
            .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
            .sorted()
            .reduce("", (joined, entry) -> joined + "\n" + entry);
    return POLICIES.computeIfAbsent(normalised, ignored -> NEXT_POLICY.getAndIncrement());
  }

  /** Called once by the provider with the JDK's own resolver, which answers every unbound name. */
  void installBuiltin(InetAddressResolver resolver) {
    builtin = resolver;
  }

  /** Whether the provider is the resolver of this JVM, so a guard reaches every client. */
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

  /** How many names are guarded. */
  int guardedNames() {
    return guards.size();
  }

  /** How many checks of a new name a full table refused. */
  long refusedChecks() {
    return refusals.get();
  }

  /**
   * What a check of {@code host} vets, every address family: the latest answer for the name when
   * younger than {@link #ANSWER_REUSE}, else a fresh one. A reused answer keeps its fetch time, so
   * a pin made from it expires as early as the first.
   */
  Answer resolveForCheck(String host) throws UnknownHostException {
    long now = nanoTime.getAsLong();
    NameKey key = key(host);
    Answer latest = key == null ? null : latestPin(key);
    if (latest != null && now - latest.fetchedAt() < ANSWER_REUSE.toNanos()) {
      return latest;
    }
    InetAddressResolver upstream = upstream();
    List<InetAddress> addresses =
        upstream == null
            ? List.of(InetAddress.getAllByName(host))
            : upstream.lookupByName(host, ALL_FAMILIES).toList();
    if (addresses.isEmpty()) {
      throw new UnknownHostException(host);
    }
    return new Answer(addresses, now);
  }

  /**
   * Pins {@code host} to {@code vetted} after a check right before a connection, without a guard.
   * Expired pins give way to a new name, a pin in force never does.
   *
   * @return {@code false}, pinning nothing, when the table is full of pins in force
   */
  boolean pinConnection(String host, Answer vetted) {
    NameKey key = key(host);
    if (key == null) {
      return false;
    }
    long now = nanoTime.getAsLong();
    Answer pinned = new Answer(List.copyOf(vetted.addresses()), vetted.fetchedAt());
    synchronized (connectionPins) {
      connectionPins.remove(key);
      Iterator<Answer> oldest = connectionPins.values().iterator();
      while (connectionPins.size() >= connectionCapacity && oldest.hasNext()) {
        if (now - oldest.next().fetchedAt() < PIN_LIFETIME.toNanos()) {
          break;
        }
        oldest.remove();
      }
      if (connectionPins.size() >= connectionCapacity) {
        refusedConnectionChecks.incrementAndGet();
        warnAtMostOncePerInterval(
            lastFullWarning,
            now,
            "Refused a target address check before a connection: {} host names are pinned right"
                + " now, the most this instance holds within the pin lifetime",
            connectionCapacity);
        return false;
      }
      connectionPins.put(key, pinned);
    }
    return true;
  }

  /** How many checks before a connection a table full of pins in force refused. */
  long refusedConnectionChecks() {
    return refusedConnectionChecks.get();
  }

  /** The youngest pin of {@code key} from either table, {@code null} if none. */
  private Answer latestPin(NameKey key) {
    Answer connection = connectionPins.get(key);
    Guard guard = guards.get(key);
    Answer guarded =
        guard == null || guard.pinned() == null
            ? null
            : new Answer(guard.pinned(), guard.pinnedAt());
    if (connection == null) {
      return guarded;
    }
    if (guarded == null) {
      return connection;
    }
    return guarded.fetchedAt() - connection.fetchedAt() > 0 ? guarded : connection;
  }

  /**
   * Guards {@code host} for {@code policy} and pins it to {@code vetted}, which the check found
   * outside every blocked range.
   *
   * @return {@code false}, guarding nothing, when the table is full and {@code host} is new
   */
  boolean admit(String host, int policy, Answer vetted) {
    NameKey key = key(host);
    if (key == null) {
      return false;
    }
    long now = nanoTime.getAsLong();
    long pinnedAt = vetted.fetchedAt();
    List<InetAddress> pinned = List.copyOf(vetted.addresses());
    Guard admitted =
        guards.compute(
            key,
            (ignored, old) -> {
              if (old == null) {
                return guards.size() >= capacity
                    ? null
                    : new Guard(pinned, pinnedAt, new int[] {policy});
              }
              return new Guard(pinned, pinnedAt, withPolicy(old.policies(), policy));
            });
    if (admitted == null) {
      refusals.incrementAndGet();
      warnAtMostOncePerInterval(
          lastFullWarning,
          now,
          "Refused the target address check of a new host name: {} names are guarded against DNS"
              + " rebinding, the most this instance holds until restart",
          capacity);
      return false;
    }
    return true;
  }

  /** Ends the guard of {@code host} by {@code policy}; another policy's guard stays. */
  void release(String host, int policy) {
    NameKey key = key(host);
    if (key == null) {
      return;
    }
    guards.computeIfPresent(
        key,
        (ignored, old) -> {
          int[] rest = Arrays.stream(old.policies()).filter(id -> id != policy).toArray();
          return rest.length == 0 ? null : new Guard(old.pinned(), old.pinnedAt(), rest);
        });
  }

  /**
   * The resolution of {@code host} for any client of this JVM; see the class comment.
   *
   * @throws UnknownHostException also when every address of a guarded name lies in a blocked range
   */
  List<InetAddress> lookup(String host, LookupPolicy policy) throws UnknownHostException {
    InetAddressResolver upstream = upstream();
    NameKey key = key(host);
    if (key == null) {
      return upstream.lookupByName(host, policy).toList();
    }
    long now = nanoTime.getAsLong();
    Answer pin = latestPin(key);
    if (pin != null && now - pin.fetchedAt() < PIN_LIFETIME.toNanos()) {
      List<InetAddress> pinned = ordered(pin.addresses(), policy);
      if (pinned.isEmpty()) {
        // Bound to vetted addresses of another family: never fall through to an unchecked answer.
        throw new UnknownHostException(host + ": bound to addresses of another family");
      }
      return named(host, pinned);
    }
    Guard guard = guards.get(key);
    if (guard == null) {
      return upstream.lookupByName(host, policy).toList();
    }
    if (guard.pinned() != null && now - guard.pinnedAt() >= PIN_LIFETIME.toNanos()) {
      guards.replace(key, guard, new Guard(null, guard.pinnedAt(), guard.policies()));
    }
    List<InetAddress> fresh = upstream.lookupByName(host, policy).toList();
    List<InetAddress> allowed =
        fresh.stream()
            .filter(address -> !TargetAddressValidator.isBlockedAddress(address))
            .toList();
    if (allowed.size() < fresh.size()) {
      warnAtMostOncePerInterval(
          lastFilterWarning,
          now,
          "Removed addresses in blocked ranges from the resolution of guarded host {}",
          host);
    }
    if (allowed.isEmpty()) {
      throw new UnknownHostException(
          host + ": every address lies in a blocked range since its target address check");
    }
    return allowed;
  }

  private void warnAtMostOncePerInterval(AtomicLong last, long now, String message, Object arg) {
    long previous = last.get();
    if ((previous == Long.MIN_VALUE || now - previous >= WARNING_INTERVAL.toNanos())
        && last.compareAndSet(previous, now)) {
      log.warn(message, arg);
    }
  }

  private static int[] withPolicy(int[] policies, int policy) {
    if (Arrays.stream(policies).anyMatch(id -> id == policy)) {
      return policies;
    }
    int[] extended = Arrays.copyOf(policies, policies.length + 1);
    extended[policies.length] = policy;
    return extended;
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

  /**
   * The key of {@code host}: ASCII letters folded to lower case, one trailing root dot dropped;
   * {@code null} for a name with a character outside ASCII, which no check admits.
   */
  static NameKey key(String host) {
    int length = host.endsWith(".") ? host.length() - 1 : host.length();
    byte[] ascii = new byte[length];
    for (int i = 0; i < length; i++) {
      char c = host.charAt(i);
      if (c > 0x7F) {
        return null;
      }
      ascii[i] = (byte) (c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c);
    }
    try {
      ByteBuffer digest = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(ascii));
      return new NameKey(digest.getLong(), digest.getLong());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java platform", e);
    }
  }
}
