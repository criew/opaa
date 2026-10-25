package io.opaa.indexing.spi;

import java.util.Set;

/**
 * What one authenticated notification asks to be looked at: the keys the connector admitted and the
 * count of reported keys it refused (outside the library's scopes). The core queues it for the
 * library it loaded itself, so a connector never names the library a run is started for.
 */
public record PushNotification(Set<String> keys, int dropped) {

  private static final PushNotification NONE = new PushNotification(Set.of(), 0);

  public PushNotification {
    keys = Set.copyOf(keys);
  }

  /** An authenticated notification that names nothing to look at. */
  public static PushNotification none() {
    return NONE;
  }

  public boolean isEmpty() {
    return keys.isEmpty();
  }
}
