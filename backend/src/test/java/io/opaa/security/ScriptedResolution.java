package io.opaa.security;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The attacker's name server of the rebinding tests, behind {@link PinnedAddressResolverProvider}:
 * a scripted name answers {@code first} once, then {@code then}; other names go to the JDK. Only
 * the {@code dnsRebindingTest} task runs without the JDK's name cache, so only there every lookup
 * of a client reaches it. Closing restores the JDK's resolver and drops the scripted bindings.
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
    int policy = ResolutionPins.policyOf(List.of());
    scripts.keySet().forEach(host -> pins.release(host, policy));
  }

  private static InetAddress literal(String address) {
    try {
      return InetAddress.getByName(address);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("not an IP literal: " + address, e);
    }
  }
}
