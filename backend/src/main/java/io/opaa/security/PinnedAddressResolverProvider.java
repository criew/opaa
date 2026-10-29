package io.opaa.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * The resolver of this JVM, registered in {@code META-INF/services}: every name any client resolves
 * - JDK {@code HttpClient}, OkHttp, Apache HttpClient, JavaMail, SMB - passes {@link
 * ResolutionPins}, so a name the target address check admitted reaches only the addresses it vetted
 * (ADR-0063). Names the check never saw go to the JDK's built-in resolver unchanged.
 */
public final class PinnedAddressResolverProvider extends InetAddressResolverProvider {

  @Override
  public InetAddressResolver get(Configuration configuration) {
    ResolutionPins pins = ResolutionPins.process();
    pins.installBuiltin(configuration.builtinResolver());
    return new PinnedResolver(pins);
  }

  @Override
  public String name() {
    return "opaa-pinned-resolution";
  }

  /**
   * Fails unless this provider resolves for the JVM; called first thing at start, while the
   * application class loader is the context class loader the JDK loads the provider with.
   *
   * @throws IllegalStateException when the JDK chose another resolver
   */
  public static void requireActive() {
    try {
      InetAddress.getAllByName("localhost");
    } catch (UnknownHostException ignored) {
      // Only the side effect matters: the first lookup makes the JDK load its resolver.
    }
    if (!ResolutionPins.process().isInstalled()) {
      throw new IllegalStateException(
          "The pinned address resolver is not the resolver of this JVM; without it the target"
              + " address check cannot hold a client to the addresses it vetted (ADR-0063).");
    }
  }

  private record PinnedResolver(ResolutionPins pins) implements InetAddressResolver {

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
        throws UnknownHostException {
      return pins.lookup(host, lookupPolicy).stream();
    }

    @Override
    public String lookupByAddress(byte[] addr) throws UnknownHostException {
      return pins.upstream().lookupByAddress(addr);
    }
  }
}
