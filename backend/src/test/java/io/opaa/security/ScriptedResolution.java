package io.opaa.security;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A name server under the test's control, the attacker's DNS of the rebinding tests: the first
 * lookup of a scripted name answers {@code first}, every later one {@code then}; every other name
 * goes to the JDK's resolver. It answers in place of the JDK's resolver behind {@link
 * PinnedAddressResolverProvider}, so the bindings of the target address check apply to it exactly
 * as to a real name server. Closing restores the JDK's resolver and drops the scripted bindings.
 * The test JVM caches no answer ({@code sun.net.inetaddr.ttl=0} in {@code build.gradle.kts}), so
 * every lookup a client makes reaches it.
 */
public final class ScriptedResolution implements AutoCloseable, InetAddressResolver {

  private record Script(InetAddress first, InetAddress then, AtomicInteger lookups) {}

  private final InetAddressResolver jdk;
  private final Map<String, Script> scripts = new ConcurrentHashMap<>();

  private ScriptedResolution(InetAddressResolver jdk) {
    this.jdk = jdk;
  }

  /** Answers in place of the JDK's resolver until {@link #close}. */
  public static ScriptedResolution install() {
    PinnedAddressResolverProvider.requireActive();
    ResolutionPins pins = ResolutionPins.process();
    ScriptedResolution resolution = new ScriptedResolution(pins.upstream());
    pins.replaceUpstream(resolution);
    return resolution;
  }

  /** {@code host} resolves to {@code first} once, then to {@code then} on every later lookup. */
  public ScriptedResolution rebinding(String host, String first, String then) {
    scripts.put(
        host.toLowerCase(Locale.ROOT),
        new Script(literal(first), literal(then), new AtomicInteger()));
    return this;
  }

  /** How often a lookup of {@code host} reached this name server. */
  public int lookups(String host) {
    return scripts.get(host.toLowerCase(Locale.ROOT)).lookups().get();
  }

  @Override
  public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
      throws UnknownHostException {
    Script script = scripts.get(host.toLowerCase(Locale.ROOT));
    if (script == null) {
      return jdk.lookupByName(host, lookupPolicy);
    }
    InetAddress answer = script.lookups().getAndIncrement() == 0 ? script.first() : script.then();
    boolean ipv4 = answer instanceof Inet4Address;
    int family = ipv4 ? LookupPolicy.IPV4 : LookupPolicy.IPV6;
    if ((lookupPolicy.characteristics() & family) == 0) {
      throw new UnknownHostException(host);
    }
    return Stream.of(InetAddress.getByAddress(host, answer.getAddress()));
  }

  @Override
  public String lookupByAddress(byte[] addr) throws UnknownHostException {
    return jdk.lookupByAddress(addr);
  }

  @Override
  public void close() {
    ResolutionPins pins = ResolutionPins.process();
    pins.replaceUpstream(null);
    scripts.keySet().forEach(pins::release);
  }

  private static InetAddress literal(String address) {
    try {
      return InetAddress.getByName(address);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("not an IP literal: " + address, e);
    }
  }
}
