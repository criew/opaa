package io.opaa.settings;

import io.opaa.common.FieldValidationException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The values of one section as read from a settings document, with typed accessors. A value of the
 * wrong type is refused with a German message naming {@code <section>.<key>} as the field, before
 * any write path sees it; whether a value lies within its bounds decides the write path.
 */
public final class SettingsValues {

  static final String INVALID_TYPE = "INVALID_TYPE";

  private final String path;
  private final Map<String, Object> values;

  SettingsValues(String path, Map<String, Object> values) {
    this.path = path;
    this.values = values;
  }

  /** The values of {@code section}; for a test or a section of nested objects. */
  public static SettingsValues of(String section, Map<String, Object> values) {
    return new SettingsValues(section, values);
  }

  public boolean requireBoolean(String key) {
    if (values.get(key) instanceof Boolean value) {
      return value;
    }
    throw invalid(key, "muss true oder false sein");
  }

  public int requireInt(String key) {
    Long value = optionalLong(key);
    if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw invalid(key, "muss eine ganze Zahl sein");
    }
    return value.intValue();
  }

  /** A whole number, or {@code null} for an absent or {@code null} value. */
  public Long optionalLong(String key) {
    Object value = values.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Integer || value instanceof Long || value instanceof Short) {
      return ((Number) value).longValue();
    }
    if (value instanceof BigInteger big && big.bitLength() < Long.SIZE) {
      return big.longValue();
    }
    if (value instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0) {
      try {
        return decimal.longValueExact();
      } catch (ArithmeticException tooLarge) {
        throw invalid(key, "muss eine ganze Zahl sein");
      }
    }
    throw invalid(key, "muss eine ganze Zahl sein");
  }

  /** A text, or {@code null} for an absent or {@code null} value. */
  public String optionalString(String key) {
    Object value = values.get(key);
    if (value == null || value instanceof String) {
      return (String) value;
    }
    throw invalid(key, "muss ein Text sein");
  }

  public String requireString(String key) {
    String value = optionalString(key);
    if (value == null) {
      throw invalid(key, "muss ein Text sein");
    }
    return value;
  }

  /** A list of texts; {@code null} reads as the empty list. */
  public List<String> requireStrings(String key) {
    Object value = values.get(key);
    if (value == null) {
      return List.of();
    }
    if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
      return list.stream().map(String.class::cast).toList();
    }
    throw invalid(key, "muss eine Liste von Texten sein");
  }

  /** A list of objects, each read under {@code <section>.<key>[<index>]}. */
  public List<SettingsValues> requireObjects(String key) {
    Object value = values.get(key);
    if (!(value instanceof List<?> list)) {
      throw invalid(key, "muss eine Liste sein");
    }
    List<SettingsValues> objects = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      if (!(list.get(i) instanceof Map<?, ?> map)) {
        throw invalid(key + "[" + i + "]", "muss ein Objekt sein");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> entry = (Map<String, Object>) map;
      objects.add(new SettingsValues(path + "." + key + "[" + i + "]", entry));
    }
    return objects;
  }

  /** An enum constant by name, or {@code null} for an absent or {@code null} value. */
  public <E extends Enum<E>> E optionalEnum(String key, Class<E> type) {
    String name = optionalString(key);
    if (name == null) {
      return null;
    }
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException unknown) {
      throw invalid(key, "hat einen unbekannten Wert: " + name);
    }
  }

  private FieldValidationException invalid(String key, String problem) {
    String field = path + "." + key;
    return FieldValidationException.of(field, INVALID_TYPE, field + " " + problem + ".");
  }
}
