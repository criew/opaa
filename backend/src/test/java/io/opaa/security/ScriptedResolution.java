package io.opaa.security;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The attacker's name server of the rebinding tests, behind {@link PinnedAddressResolverProvider}:
 * a scripted name answers {@code first} once, then {@code then}, a name under an answered domain
 * its one address; other names go to the JDK. Only the {@code dnsRebindingTest} task runs without
 * the JDK's name cache, so only there every lookup of a client reaches it. Closing restores the
 * JDK's resolver and drops the scripted bindings.
 */
public final class ScriptedResolution implements AutoCloseable, InetAddressResolver {

  private record Script(InetAddress first, InetAddress then, AtomicInteger lookups) {}

  private final InetAddressResolver jdk;
  private final Map<String, Script> scripts = new ConcurrentHashMap<>();
  private final Map<String, InetAddress> domains = new ConcurrentHashMap<>();
  private final Set<String> asked = ConcurrentHashMap.newKeySet();

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

  /**
   * Every name under {@code domain} that has no script of its own resolves to {@code address}, as
   * the name server of a domain with ever new names does; see {@link #namesAskedUnder}.
   */
  public ScriptedResolution answeringUnder(String domain, String address) {
    domains.put(domain.toLowerCase(Locale.ROOT), literal(address));
    return this;
  }

  /** The distinct names under {@code domain} a lookup asked this name server for. */
  public Set<String> namesAskedUnder(String domain) {
    String suffix = "." + domain.toLowerCase(Locale.ROOT);
    return asked.stream().filter(name -> name.endsWith(suffix)).collect(Collectors.toSet());
  }

  @Override
  public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
      throws UnknownHostException {
    String name = host.toLowerCase(Locale.ROOT);
    Script script = scripts.get(name);
    if (script == null) {
      InetAddress underDomain = answerUnderDomain(name);
      if (underDomain == null) {
        return jdk.lookupByName(host, lookupPolicy);
      }
      asked.add(name);
      return Stream.of(InetAddress.getByAddress(host, underDomain.getAddress()));
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

  private InetAddress answerUnderDomain(String name) {
    return domains.entrySet().stream()
        .filter(domain -> name.endsWith("." + domain.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private static InetAddress literal(String address) {
    try {
      return InetAddress.getByName(address);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("not an IP literal: " + address, e);
    }
  }
}
