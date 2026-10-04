package io.opaa.indexing.source;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The connector settings keys a connection profile may set, in display order. */
public record ProfileDefaults(List<DefaultKey> keys) {

  private static final ProfileDefaults NONE = new ProfileDefaults(List.of());

  public ProfileDefaults {
    keys = keys == null ? List.of() : List.copyOf(keys);
    Set<String> seen = new HashSet<>();
    for (DefaultKey key : keys) {
      if (!seen.add(key.key())) {
        throw new IllegalArgumentException("profile default " + key.key() + " is named twice");
      }
    }
  }

  public static ProfileDefaults none() {
    return NONE;
  }

  public static ProfileDefaults of(DefaultKey... keys) {
    return new ProfileDefaults(List.of(keys));
  }

  public Optional<DefaultKey> key(String name) {
    return keys.stream().filter(key -> key.key().equals(name)).findFirst();
  }

  public boolean isEmpty() {
    return keys.isEmpty();
  }
}
