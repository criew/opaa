package io.opaa.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * The {@link DnsResolver} of every Apache HTTP client that connects to a validated target: the
 * client's only resolution runs through a {@link ConnectionAddressResolver}, and the socket
 * connects to exactly the addresses that passed it. Proxy hosts are exempt - they are operator
 * configuration checked where they are configured, and with a proxy the target name is resolved by
 * the proxy.
 */
public final class CheckedDnsResolver implements DnsResolver {

  private final ConnectionAddressResolver resolver;
  private final Set<String> proxyHosts = ConcurrentHashMap.newKeySet();

  public CheckedDnsResolver(ConnectionAddressResolver resolver) {
    this.resolver = resolver;
  }

  /** Exempts {@code host} as a proxy of this client; {@code null} is ignored. */
  public CheckedDnsResolver exemptProxy(String host) {
    if (host != null && !host.isBlank()) {
      proxyHosts.add(host.toLowerCase(Locale.ROOT));
    }
    return this;
  }

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    return resolveChecked(host, -1);
  }

  @Override
  public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
    return Arrays.stream(resolveChecked(host, port))
        .map(address -> new InetSocketAddress(address, port))
        .toList();
  }

  @Override
  public String resolveCanonicalHostname(String host) throws UnknownHostException {
    return SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host);
  }

  private InetAddress[] resolveChecked(String host, int port) throws UnknownHostException {
    if (proxyHosts.contains(host.toLowerCase(Locale.ROOT))) {
      return InetAddress.getAllByName(host);
    }
    try {
      return resolver.resolve(host, port);
    } catch (TargetAddressValidator.TargetAddressBlockedException e) {
      throw new RejectedAddressException(e);
    } catch (UnknownHostException e) {
      throw e;
    } catch (IOException e) {
      UnknownHostException unresolved = new UnknownHostException(host);
      unresolved.initCause(e);
      throw unresolved;
    }
  }

  /**
   * Carries a refused address through the resolver contract, which only admits {@link
   * UnknownHostException}; {@link #rejection()} is the German, user-facing refusal.
   */
  public static final class RejectedAddressException extends UnknownHostException {

    private final TargetAddressValidator.TargetAddressBlockedException rejection;

    RejectedAddressException(TargetAddressValidator.TargetAddressBlockedException rejection) {
      super(rejection.getMessage());
      initCause(rejection);
      this.rejection = rejection;
    }

    public TargetAddressValidator.TargetAddressBlockedException rejection() {
      return rejection;
    }
  }
}
