package io.opaa.test;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A {@link SelfDisclosureSink} for exporter tests that keeps every record in memory and refuses
 * what the archive refuses: an unknown or reopened section, a key off the positive list, a derived
 * field carrying more than yes/no or a fixed label, and a value of an unsupported type.
 */
public final class CapturingSelfDisclosureSink implements SelfDisclosureSink {

  private final SelfDisclosureCategory category;
  private final Map<String, List<Map<String, Object>>> sections = new LinkedHashMap<>();
  private int lastOpened = -1;

  public CapturingSelfDisclosureSink(SelfDisclosureCategory category) {
    this.category = category;
  }

  @Override
  public RecordWriter section(String key) {
    Section section = category.section(key);
    if (section == null) {
      throw new IllegalStateException("No section " + key + " in " + category.id());
    }
    int index = category.sections().indexOf(section);
    if (index <= lastOpened) {
      throw new IllegalStateException("Section " + key + " opened out of order or twice");
    }
    lastOpened = index;
    List<Map<String, Object>> records = new ArrayList<>();
    sections.put(key, records);
    return record -> records.add(checked(section, record));
  }

  /** The records of section {@code key}, empty when it was never opened. */
  public List<Map<String, Object>> records(String key) {
    return sections.getOrDefault(key, List.of());
  }

  /** All records of every section, as one text for searching values. */
  public String text() {
    return sections.toString();
  }

  private Map<String, Object> checked(Section section, Map<String, ?> record) {
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, ?> entry : record.entrySet()) {
      Field field = section.field(entry.getKey());
      if (field == null) {
        throw new IllegalStateException(
            "Field " + entry.getKey() + " is not on the positive list of " + section.key());
      }
      Object value = entry.getValue();
      if (field.derived()
          && value != null
          && !(value instanceof Boolean)
          && !(value instanceof String label && SelfDisclosureLabels.ALL.contains(label))) {
        throw new IllegalStateException("Derived field " + field.key() + " carries " + value);
      }
      if (!supported(value)) {
        throw new IllegalStateException("Field " + field.key() + " carries a " + value.getClass());
      }
      copy.put(entry.getKey(), value);
    }
    return copy;
  }

  private static boolean supported(Object value) {
    if (value instanceof List<?> list) {
      return list.stream().allMatch(String.class::isInstance);
    }
    return value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Instant
        || value instanceof LocalDate
        || value instanceof UUID;
  }
}
