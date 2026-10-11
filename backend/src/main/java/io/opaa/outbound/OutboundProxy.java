package io.opaa.outbound;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The stored outbound proxy and the routing of {@link OutboundProxySelector}. The selector takes
 * the stored value at startup and every change only after its transaction committed, so a rolled
 * back change never routes a connection.
 */
@Service
public class OutboundProxy {

  /**
   * The stored values.
   *
   * @param proxy {@code null} when every connection goes direct
   * @param noProxy the normalized exclusion list
   * @param updatedAt {@code null} while nothing was ever stored
   */
  public record Settings(ProxyAddress proxy, List<String> noProxy, Instant updatedAt) {
    public Settings {
      noProxy = List.copyOf(noProxy);
    }

    OutboundProxySelector.Snapshot snapshot() {
      return new OutboundProxySelector.Snapshot(proxy, ProxyExclusions.of(noProxy));
    }
  }

  /** Published inside the changing transaction; the selector re-reads the row after its commit. */
  record Changed() {}

  private final OutboundProxySettingRepository repository;
  private final ApplicationEventPublisher events;
  private final OutboundProxySelector selector = OutboundProxySelector.instance();

  OutboundProxy(OutboundProxySettingRepository repository, ApplicationEventPublisher events) {
    this.repository = repository;
    this.events = events;
  }

  /**
   * Routes by the stored value - at startup, after every committed change, and whenever the row
   * changed underneath. Synchronized, so the last applied state is the last one read: two changes
   * committed close together never leave the older one in force.
   */
  @PostConstruct
  public synchronized void reload() {
    OutboundProxyInstaller.install();
    selector.apply(read().snapshot());
  }

  @Transactional(readOnly = true)
  public Settings read() {
    return repository
        .findSingleton()
        .map(OutboundProxy::toSettings)
        .orElseGet(() -> new Settings(null, List.of(), null));
  }

  /**
   * Stores {@code proxy} ({@code null} for none) and the exclusion list; the caller has validated
   * both ({@link ProxyAddress#parse}, {@link ProxyExclusions#invalidEntries}).
   */
  @Transactional
  public Settings change(ProxyAddress proxy, List<String> noProxy) {
    List<String> normalized = ProxyExclusions.normalize(noProxy);
    if (!ProxyExclusions.invalidEntries(normalized).isEmpty()) {
      throw new IllegalArgumentException("invalid exclusion entries: " + normalized);
    }
    String column = String.join(OutboundProxySetting.SEPARATOR, normalized);
    if (proxy == null) {
      repository.upsertDirect(column);
    } else {
      repository.upsertProxy(proxy.host(), proxy.port(), column);
    }
    events.publishEvent(new Changed());
    return read();
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onChanged(Changed changed) {
    reload();
  }

  /** Whether a connection to {@code host} goes through the proxy right now. */
  public boolean isProxied(String host) {
    return selector.isProxied(host);
  }

  /** The proxy an {@code http(s)} request to {@code target} goes through right now, if any. */
  public Optional<ProxyAddress> proxyFor(URI target) {
    return selector.proxyFor(target);
  }

  private static Settings toSettings(OutboundProxySetting row) {
    ProxyAddress proxy =
        row.getProxyHost() == null || row.getProxyPort() == null
            ? null
            : new ProxyAddress(row.getProxyHost(), row.getProxyPort());
    String column = row.getNoProxy() == null ? "" : row.getNoProxy();
    List<String> entries =
        ProxyExclusions.normalize(Arrays.asList(column.split(OutboundProxySetting.SEPARATOR)));
    return new Settings(proxy, entries, row.getUpdatedAt());
  }
}
