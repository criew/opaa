package io.opaa.llm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.opaa.instance.BusBoundCache;
import io.opaa.instance.InvalidationBus;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.observability.MeteredCaches;
import io.opaa.security.CipherBinding;
import io.opaa.security.SettingsEncryptor;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stored endpoint of one optional model role (ADR-0060, Entscheidung 10), the factory default
 * while its row is missing. Read on every query and availability check, so it is cached; the
 * trigger on the role's table drops the cache on every instance through the {@link InvalidationBus}
 * (ADR-0053, Entscheidung 5).
 *
 * <p>A stored key that cannot be decrypted is passed on as no key and logged: the endpoint then
 * refuses the call, which the role reports as its state.
 */
public abstract class OptionalRoleEndpoints<E extends OptionalRoleEndpoint>
    implements InvalidationSubscriber, MeteredCaches {

  private static final Logger log = LoggerFactory.getLogger(OptionalRoleEndpoints.class);

  private final String topic;
  private final String cacheName;
  private final SettingsEncryptor settingsEncryptor;
  private final BusBoundCache<Boolean, E> cache;

  protected OptionalRoleEndpoints(
      String topic, String cacheName, SettingsEncryptor settingsEncryptor, InvalidationBus bus) {
    this.topic = topic;
    this.cacheName = cacheName;
    this.settingsEncryptor = settingsEncryptor;
    this.cache =
        new BusBoundCache<>(Caffeine.newBuilder().maximumSize(1).recordStats().build(), bus);
  }

  /** The endpoint in force; never blocks on the network. */
  public E current() {
    return cache.get(Boolean.TRUE, key -> load());
  }

  /** Reads the role's row, or answers the factory default without one. */
  protected abstract E load();

  @Override
  public Set<String> invalidationTopics() {
    return Set.of(topic);
  }

  @Override
  public void invalidateAll() {
    cache.invalidateAll();
  }

  @Override
  public Map<String, Cache<?, ?>> meteredCaches() {
    return Map.of(cacheName, cache.statistics());
  }

  /** {@code ciphertext} decrypted, empty for none or one that cannot be decrypted. */
  protected String decrypt(String ciphertext, CipherBinding binding, String role) {
    if (ciphertext == null) {
      return "";
    }
    try {
      return settingsEncryptor.decrypt(ciphertext, binding);
    } catch (IllegalStateException e) {
      log.error(
          "The stored key of the {} model cannot be decrypted ({}); calls go without a key until"
              + " OPAA_SETTINGS_ENCRYPTION_KEY matches or the key is entered again",
          role,
          e.getClass().getSimpleName());
      return "";
    }
  }
}
