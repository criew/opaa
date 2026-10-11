package io.opaa.test;

import io.opaa.outbound.OutboundProxyInstaller;
import io.opaa.outbound.OutboundProxySelector;
import io.opaa.outbound.ProxyAddress;
import io.opaa.outbound.ProxyExclusions;
import java.net.ProxySelector;
import java.util.List;

/**
 * Routes this JVM through an outbound proxy for one test, the way the application does after its
 * start: the outbound selector as the default {@link ProxySelector}. {@link #close} goes direct
 * again and restores the previous default. Clients must be built after {@link #through}, since the
 * JDK client takes the default selector when it is built.
 */
public final class OutboundProxyRoute implements AutoCloseable {

  private final ProxySelector previous;

  private OutboundProxyRoute(ProxySelector previous) {
    this.previous = previous;
  }

  public static OutboundProxyRoute through(String proxy, String... exclusions) {
    ProxySelector previous = ProxySelector.getDefault();
    OutboundProxyInstaller.install();
    OutboundProxySelector.instance()
        .apply(
            new OutboundProxySelector.Snapshot(
                ProxyAddress.parse(proxy).orElseThrow(), ProxyExclusions.of(List.of(exclusions))));
    return new OutboundProxyRoute(previous);
  }

  @Override
  public void close() {
    OutboundProxySelector.instance().apply(OutboundProxySelector.Snapshot.direct());
    ProxySelector.setDefault(previous);
  }
}
