package io.opaa.indexing.metadata;

import io.opaa.common.ValidationException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One condition of a {@link MetadataFilter} on a format field: the values a document's identifier
 * may take, matched <b>exactly</b>. A field the schema does not declare filterable carries no
 * condition at all - the Betreff is a display field, and a filter naming it is a caller error
 * (400), not a silently dropped condition.
 *
 * <p>A document without a value for the field is never excluded (Leerwert-Regel), which is why the
 * field is global rather than library-scoped: a document of any library that is not a mail simply
 * has no sender.
 */
public record FormatFieldCondition(FormatMetadataField field, Set<String> values) {

  public FormatFieldCondition {
    if (field == null) {
      throw new ValidationException("Ein Formatfeld-Filter braucht sein Feld");
    }
    values = values == null ? Set.of() : Set.copyOf(values);
  }

  /**
   * The API shape - a field key and its values - as a condition, rejecting an unknown key, a
   * non-filterable field and a value the field's pattern does not accept with 400.
   */
  public static FormatFieldCondition parse(String fieldKey, Collection<String> values) {
    FormatMetadataField field =
        FormatMetadataField.fromKey(fieldKey)
            .orElseThrow(
                () -> new ValidationException("Unbekanntes Feld im Metadatenfilter: " + fieldKey));
    if (!field.isFilterable()) {
      throw new ValidationException("Das Feld " + field.label() + " ist nicht filterbar");
    }
    Set<String> accepted = new LinkedHashSet<>();
    if (values != null) {
      for (String value : values) {
        String stripped = value == null ? null : value.strip();
        if (stripped == null || stripped.isEmpty()) {
          continue;
        }
        if (!field.accepts(stripped)) {
          throw new ValidationException(
              "Ungültiger Wert im Filter auf " + field.label() + ": " + stripped);
        }
        accepted.add(stripped);
      }
    }
    return new FormatFieldCondition(field, accepted);
  }

  /** A condition that constrains nothing - the caller drops it rather than translating it. */
  public boolean isEmpty() {
    return values.isEmpty();
  }

  public String fieldKey() {
    return field.key();
  }

  public String chunkKey() {
    return field.chunkKey();
  }

  public String presenceChunkKey() {
    return field.presenceChunkKey();
  }

  /** Whether a document carrying {@code storedValue} qualifies - no value never disqualifies. */
  public boolean matches(String storedValue) {
    return storedValue == null || values.contains(storedValue);
  }
}
