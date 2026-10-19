package io.opaa.compliance;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/** Reads the effective value of a configuration key the way the binding sees it. */
public final class PropertyValues {

  private PropertyValues() {}

  /**
   * The value of {@code key}, also when it is bound as an indexed list (joined by commas) or as a
   * map (sorted {@code name=value} pairs joined by commas); {@code null} if unset.
   */
  public static String read(Environment environment, String key) {
    String value = environment.getProperty(key);
    if (value != null) {
      return value;
    }
    Binder binder = Binder.get(environment);
    String list =
        binder
            .bind(key, Bindable.listOf(String.class))
            .map(values -> String.join(",", values))
            .orElse(null);
    if (list != null) {
      return list;
    }
    return binder
        .bind(key, Bindable.mapOf(String.class, String.class))
        .map(PropertyValues::joined)
        .orElse(null);
  }

  private static String joined(Map<String, String> map) {
    return new TreeMap<>(map)
        .entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(","));
  }
}
