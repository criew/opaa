package io.opaa.indexing.source;

import io.opaa.api.types.ProfileDefaultKind;
import java.util.List;
import java.util.Objects;

/**
 * One connector settings key a connection profile may set, with the German label of its field;
 * {@code choices} lists the values of a {@code CHOICE} and is empty for every other kind. A {@code
 * profileOnly} key is the profile's alone: under a profile a library never carries it, also where
 * the profile leaves it empty.
 */
public record DefaultKey(
    String key, String label, ProfileDefaultKind kind, List<String> choices, boolean profileOnly) {

  public DefaultKey {
    if (key == null || key.isBlank() || label == null || label.isBlank()) {
      throw new IllegalArgumentException("a profile default names its key and label");
    }
    Objects.requireNonNull(kind, "kind");
    choices = choices == null ? List.of() : List.copyOf(choices);
    if ((kind == ProfileDefaultKind.CHOICE) == choices.isEmpty()) {
      throw new IllegalArgumentException("only a choice, and every choice, lists its values");
    }
  }

  public DefaultKey(String key, String label, ProfileDefaultKind kind, List<String> choices) {
    this(key, label, kind, choices, false);
  }

  public static DefaultKey text(String key, String label) {
    return new DefaultKey(key, label, ProfileDefaultKind.TEXT, List.of());
  }

  public static DefaultKey bool(String key, String label) {
    return new DefaultKey(key, label, ProfileDefaultKind.BOOLEAN, List.of());
  }

  public static DefaultKey choice(String key, String label, String... choices) {
    return new DefaultKey(key, label, ProfileDefaultKind.CHOICE, List.of(choices));
  }

  /** This key as one only the profile sets. */
  public DefaultKey onlyOnProfile() {
    return new DefaultKey(key, label, kind, choices, true);
  }
}
