package io.opaa.indexing.source;

import io.opaa.common.ValidationException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The connector settings keys a connection profile may set, in display order, and the reading of a
 * profile's defaults against them - without the connector, so a profile is checked by its
 * declaration alone.
 */
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

  /**
   * {@code requested} in normalised form: only declared keys, each of its kind, a blank text or
   * {@code null} dropped; {@code null} when nothing is left.
   *
   * @throws ValidationException (German 400) for an undeclared key or a value of the wrong kind
   */
  public ConnectorData read(ConnectorData requested) {
    if (requested == null) {
      return null;
    }
    Map<String, Object> read = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : requested.asMap().entrySet()) {
      String name = entry.getKey();
      DefaultKey key =
          key(name)
              .orElseThrow(
                  () ->
                      new ValidationException(
                          "connectorSettings: das Feld "
                              + name
                              + " kann ein Zugang nicht vorgeben"));
      Object value = valueOf(key, entry.getValue());
      if (value != null) {
        read.put(name, value);
      }
    }
    return read.isEmpty() ? null : ConnectorData.of(read);
  }

  private static Object valueOf(DefaultKey key, Object value) {
    if (value == null) {
      return null;
    }
    String field = "connectorSettings." + key.key();
    return switch (key.kind()) {
      case TEXT -> {
        if (!(value instanceof String text)) {
          throw new ValidationException(field + " muss ein Text sein");
        }
        yield text.isBlank() ? null : text.trim();
      }
      case BOOLEAN -> {
        if (!(value instanceof Boolean)) {
          throw new ValidationException(field + " muss ja oder nein sein");
        }
        yield value;
      }
      case CHOICE -> {
        if (!(value instanceof String choice) || !key.choices().contains(choice)) {
          throw new ValidationException(
              field + " muss einer der Werte " + String.join(", ", key.choices()) + " sein");
        }
        yield choice;
      }
    };
  }
}
