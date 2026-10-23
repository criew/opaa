package io.opaa.compliance;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One category of the self-disclosure with its positive list: the data entry {@code id} of the fact
 * manifest, a German title and the sections whose records the exporter writes. A record is flat; a
 * sub-list (a chat's messages, an answer's sources) is a section of its own whose records refer
 * back by a field. {@code SelfDisclosureCoverageTest} holds the fields against the manifest.
 *
 * @param id the {@code id} of the manifest data entry, also the JSON file name
 * @param title the German heading of the category
 * @param sections the sections in the order the exporter writes them
 */
public record SelfDisclosureCategory(String id, String title, List<Section> sections) {

  public SelfDisclosureCategory {
    requireText(id, "id");
    requireText(title, "title");
    sections = List.copyOf(sections);
    if (sections.isEmpty()) {
      throw new IllegalArgumentException("Category " + id + " has no section");
    }
    requireUnique(sections.stream().map(Section::key).toList(), "section of " + id);
  }

  /** The section named {@code key}, or {@code null}. */
  public Section section(String key) {
    return sections.stream().filter(s -> s.key().equals(key)).findFirst().orElse(null);
  }

  /**
   * A list of records of one shape.
   *
   * @param key stable key, also the JSON property of the section
   * @param title the German heading of the table in the overview
   * @param fields the positive list: the only keys a record of this section may carry
   */
  public record Section(String key, String title, List<Field> fields) {

    public Section {
      requireText(key, "key");
      requireText(title, "title");
      fields = List.copyOf(fields);
      if (fields.isEmpty()) {
        throw new IllegalArgumentException("Section " + key + " has no field");
      }
      requireUnique(fields.stream().map(Field::key).toList(), "field of " + key);
    }

    /** The field named {@code key}, or {@code null}. */
    public Field field(String key) {
      return fields.stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
    }
  }

  /**
   * One field of the positive list.
   *
   * @param key stable key, also the JSON property of a record
   * @param label the German column heading of the overview
   * @param columns the manifest columns ({@code table.column}) the value comes from
   * @param derived whether the value only says something about the columns without repeating them:
   *     a yes/no or one of {@link SelfDisclosureLabels#ALL}, enforced by the sink. Only a derived
   *     field may name a column the manifest excludes from the disclosure.
   */
  public record Field(String key, String label, List<String> columns, boolean derived) {

    public Field {
      requireText(key, "key");
      requireText(label, "label");
      columns = List.copyOf(columns);
      if (columns.isEmpty()) {
        throw new IllegalArgumentException("Field " + key + " names no column");
      }
      for (String column : columns) {
        if (column == null || !column.matches("[a-z0-9_]+\\.[a-z0-9_]+")) {
          throw new IllegalArgumentException(
              "Field " + key + " names '" + column + "', not table.column");
        }
      }
    }

    /** A field repeating the value of {@code columns}. */
    public static Field of(String key, String label, String... columns) {
      return new Field(key, label, List.of(columns), false);
    }

    /** A {@link #derived()} field about {@code columns}. */
    public static Field derived(String key, String label, String... columns) {
      return new Field(key, label, List.of(columns), true);
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requireUnique(List<String> keys, String what) {
    Set<String> seen = new HashSet<>();
    for (String key : keys) {
      if (!seen.add(Objects.requireNonNull(key))) {
        throw new IllegalArgumentException("Duplicate " + what + ": " + key);
      }
    }
  }
}
