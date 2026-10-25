package io.opaa.settings;

import io.opaa.common.FieldValidationException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The values of one section as read from a settings document, with typed accessors. A value of the
 * wrong type is refused with a German message naming {@code <section>.<key>} as the field, before
 * any write path sees it; whether a value lies within its bounds decides the write path.
 */
public final class SettingsValues {

  static final String INVALID_TYPE = "INVALID_TYPE";

  /** How many characters of a text from the document a message repeats at most. */
  static final int SHOWN_LENGTH = 40;

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

  /** A list of texts; {@code null} is refused, the empty list is written as {@code []}. */
  public List<String> requireStrings(String key) {
    Object value = values.get(key);
    if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
      return list.stream().map(String.class::cast).toList();
    }
    throw invalid(key, "muss eine Liste von Texten sein");
  }

  /**
   * The object under {@code key}, read under {@code <section>.<key>}, or {@code null} for an absent
   * or {@code null} value; a key it does not know among {@code allowed} is refused.
   */
  public SettingsValues optionalObject(String key, Set<String> allowed) {
    Object value = values.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw invalid(key, "muss ein Objekt sein");
    }
    for (Object nested : map.keySet()) {
      if (!allowed.contains(nested)) {
        throw invalid(key, "kennt die Angabe " + shown(String.valueOf(nested)) + " nicht");
      }
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> entry = (Map<String, Object>) map;
    return new SettingsValues(path + "." + key, entry);
  }

  /** The path under which this object's fields are named, {@code <section>} or deeper. */
  public String path() {
    return path;
  }

  /**
   * A text from the document as it may appear in a message: control characters removed, cut to
   * {@link #SHOWN_LENGTH} characters, in quotes.
   */
  public static String shown(String text) {
    String clean = text.replaceAll("\\p{Cntrl}", "");
    if (clean.length() > SHOWN_LENGTH) {
      clean = clean.substring(0, SHOWN_LENGTH) + "…";
    }
    return "„" + clean + "“";
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
      throw invalid(key, "hat einen unbekannten Wert: " + shown(name));
    }
  }

  private FieldValidationException invalid(String key, String problem) {
    String field = path + "." + key;
    return FieldValidationException.of(field, INVALID_TYPE, field + " " + problem + ".");
  }
}
