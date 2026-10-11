package io.opaa.outbound;

import io.opaa.common.NumericAddress;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JVM-wide route of every outgoing connection: through the outbound proxy, or {@link
 * Proxy#NO_PROXY}. Every client that asks the default {@link ProxySelector} - the JDK {@code
 * HttpClient} built without {@code .proxy()}, {@code HttpURLConnection}, OkHttp - asks this one per
 * connection, so a change takes effect without a restart.
 *
 * <p>Invariant: only {@code http} and {@code https} ever go through the proxy. Every other scheme -
 * above all {@code socket://}, which {@code new Socket()} asks for SMB, SMTP and JDBC - is always
 * direct, and so are loopback targets and the exclusion list.
 */
public final class OutboundProxySelector extends ProxySelector {

  private static final Logger log = LoggerFactory.getLogger(OutboundProxySelector.class);

  private static final OutboundProxySelector INSTANCE = new OutboundProxySelector();

  private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);

  /**
   * What the selector routes by; {@link #direct()} without a proxy.
   *
   * @param proxy {@code null} for none
   */
  public record Snapshot(ProxyAddress proxy, ProxyExclusions exclusions) {
    public Snapshot {
      exclusions = exclusions == null ? ProxyExclusions.none() : exclusions;
    }

    public static Snapshot direct() {
      return new Snapshot(null, ProxyExclusions.none());
    }
  }

  private volatile Snapshot snapshot = Snapshot.direct();

  private OutboundProxySelector() {}

  public static OutboundProxySelector instance() {
    return INSTANCE;
  }

  /** Routes every following connection by {@code next}. */
  public void apply(Snapshot next) {
    snapshot = Objects.requireNonNull(next, "snapshot");
  }

  public Snapshot current() {
    return snapshot;
  }

  @Override
  public List<Proxy> select(URI uri) {
    if (uri == null) {
      throw new IllegalArgumentException("uri must not be null");
    }
    String scheme = uri.getScheme();
    if (scheme == null) {
      return DIRECT;
    }
    String lower = scheme.toLowerCase(Locale.ROOT);
    if (!lower.equals("http") && !lower.equals("https")) {
      return DIRECT;
    }
    Snapshot current = snapshot;
    String host = uri.getHost();
    if (!isProxied(current, host)) {
      return DIRECT;
    }
    return List.of(new Proxy(Proxy.Type.HTTP, current.proxy().socketAddress()));
  }

  @Override
  public void connectFailed(URI uri, SocketAddress address, IOException failure) {
    log.debug("Connection to {} via proxy {} failed: {}", uri, address, failure.toString());
  }

  /**
   * Whether a connection to {@code host} goes through the proxy - the same decision {@link #select}
   * takes for an {@code http(s)} address of that host.
   */
  public boolean isProxied(String host) {
    return isProxied(snapshot, host);
  }

  /** The proxy an {@code http(s)} request to {@code target} goes through, if any. */
  public Optional<ProxyAddress> proxyFor(URI target) {
    if (target == null) {
      return Optional.empty();
    }
    Snapshot current = snapshot;
    String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
    if ((!scheme.equals("http") && !scheme.equals("https"))
        || !isProxied(current, target.getHost())) {
      return Optional.empty();
    }
    return Optional.of(current.proxy());
  }

  private static boolean isProxied(Snapshot current, String host) {
    if (current.proxy() == null || host == null || host.isBlank()) {
      return false;
    }
    return !isLoopback(host) && !current.exclusions().matches(host);
  }

  /** {@code localhost}, a name under {@code .localhost}, or a loopback literal - never DNS. */
  static boolean isLoopback(String host) {
    String value = ProxyExclusions.stripBrackets(host.strip().toLowerCase(Locale.ROOT));
    if (value.endsWith(".")) {
      value = value.substring(0, value.length() - 1);
    }
    if (value.equals("localhost") || value.endsWith(".localhost")) {
      return true;
    }
    if (!NumericAddress.isNumeric(value)) {
      return false;
    }
    try {
      InetAddress address = InetAddress.getByName(value);
      if (address.isLoopbackAddress()) {
        return true;
      }
      byte[] bytes = address.getAddress();
      return bytes.length == 16 && isMappedIpv4Loopback(bytes);
    } catch (UnknownHostException notALiteral) {
      return false;
    }
  }

  private static boolean isMappedIpv4Loopback(byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF && bytes[12] == 127;
  }
}
