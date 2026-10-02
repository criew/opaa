package io.opaa.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DNS rebinding under test control: {@code host} answers a public address on its first lookup and
 * the system's own answer on every later one. With {@code localhost} that is a name that passes the
 * address check and then points at the loopback - and at the local test server listening there.
 * Every other name resolves normally.
 */
public final class RebindingHostLookup implements TargetAddressValidator.HostLookup {

  /** {@code 93.184.216.34}, a routable address outside every blocked range. */
  public static final InetAddress PUBLIC_ADDRESS = publicAddress();

  private final String host;
  private final AtomicInteger lookups = new AtomicInteger();

  public RebindingHostLookup(String host) {
    this.host = host;
  }

  @Override
  public InetAddress[] lookup(String name) throws UnknownHostException {
    if (name.equalsIgnoreCase(host) && lookups.getAndIncrement() == 0) {
      return new InetAddress[] {PUBLIC_ADDRESS};
    }
    return InetAddress.getAllByName(name);
  }

  /** How often {@code host} was looked up so far. */
  public int lookups() {
    return lookups.get();
  }

  private static InetAddress publicAddress() {
    try {
      return InetAddress.getByAddress(new byte[] {93, (byte) 184, (byte) 216, 34});
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }
}
