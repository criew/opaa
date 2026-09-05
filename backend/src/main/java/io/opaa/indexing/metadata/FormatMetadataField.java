package io.opaa.indexing.metadata;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The built-in format fields (metadata-schema.md, Teil II (c) "Formatfelder"): schema fields only
 * certain pipelines can fill, because only certain formats declare them. A value is read verbatim
 * from the document and therefore always {@code DETERMINISTIC}; the value rows live in {@code
 * document_metadata_values} under the {@code fmt:} namespace, beside the core and library fields.
 *
 * <p>Only a field with {@link #isFilterable()} rides on the chunks and can appear in a {@link
 * MetadataFilter}; the others reach the Beleg alone. A filterable field's value is an identifier
 * checked against {@link #valuePattern()} and matched exactly - never as a substring, the same rule
 * a PATTERN library field follows.
 */
public enum FormatMetadataField {
  MAIL_SENDER("mail_sender", "Absender", "email", true, false, "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"),
  MAIL_RECIPIENTS("mail_recipients", "An", "email", false, true, null),
  MAIL_SUBJECT("mail_subject", "Betreff", "email", false, false, null);

  /** The {@code document_metadata_values.field_key} namespace of every format field. */
  public static final String FIELD_KEY_PREFIX = "fmt:";

  /** The chunk metadata key prefix of a filterable format field's value. */
  public static final String CHUNK_KEY_PREFIX = "ff_";

  /**
   * "This document has a value for the field", the only value this key ever carries - the format
   * twin of {@link LibraryMetadataFieldKeys#PRESENCE_CHUNK_KEY_PREFIX} and needed for the same
   * reason: the Leerwert rule is expressed as NOT IN over a closed value set, which a sender
   * address is not.
   */
  public static final String PRESENCE_CHUNK_KEY_PREFIX = "ffs_";

  /** The one value {@link #PRESENCE_CHUNK_KEY_PREFIX} carries. */
  public static final String PRESENCE_VALUE = "SET";

  /** Every chunk key the format fields own - what a chunk rewrite may remove. */
  public static final Set<String> MANAGED_CHUNK_KEYS = managedChunkKeys();

  /**
   * Upper bound of a stored value, in characters. A display field is cut back to it - a
   * distribution list of two hundred recipients is a Beleg-Zeile nobody reads and would not fit
   * {@code document_metadata_values.text_value} either; a filterable field rejects an over-long
   * value instead, since a truncated identifier matches nothing.
   */
  public static final int MAX_VALUE_LENGTH = 200;

  private final String key;
  private final String label;
  private final String pipelineId;
  private final boolean filterable;
  private final boolean detailOnly;
  private final Pattern valuePattern;

  FormatMetadataField(
      String key,
      String label,
      String pipelineId,
      boolean filterable,
      boolean detailOnly,
      String valuePattern) {
    this.key = key;
    this.label = label;
    this.pipelineId = pipelineId;
    this.filterable = filterable;
    this.detailOnly = detailOnly;
    this.valuePattern = valuePattern == null ? null : Pattern.compile(valuePattern);
  }

  /** The format field named {@code key}, or empty for any other key. */
  public static Optional<FormatMetadataField> fromKey(String key) {
    for (FormatMetadataField field : values()) {
      if (field.key.equals(key)) {
        return Optional.of(field);
      }
    }
    return Optional.empty();
  }

  /** The format field behind a namespaced {@code fmt:<key>} value key, or empty. */
  public static Optional<FormatMetadataField> fromFieldKey(String documentFieldKey) {
    if (documentFieldKey == null || !documentFieldKey.startsWith(FIELD_KEY_PREFIX)) {
      return Optional.empty();
    }
    return fromKey(documentFieldKey.substring(FIELD_KEY_PREFIX.length()));
  }

  public String key() {
    return key;
  }

  public String label() {
    return label;
  }

  public boolean isFilterable() {
    return filterable;
  }

  /**
   * The pipeline whose format declares this field - the only documents the field is ever about. A
   * document of any other pipeline has no value for it by construction, which is not the same as
   * "value missing".
   */
  public String pipelineId() {
    return pipelineId;
  }

  /**
   * Whether the value belongs into the Beleg detail view only, not into the one-line
   * Fundstellenzeile: an unbounded recipient list identifies no passage and would push the fields
   * that do out of a line meant to be read in the flow of an answer.
   */
  public boolean isDetailOnly() {
    return detailOnly;
  }

  public Pattern valuePattern() {
    return valuePattern;
  }

  /** The {@code document_metadata_values.field_key} of this field. */
  public String documentFieldKey() {
    return FIELD_KEY_PREFIX + key;
  }

  /** The chunk metadata key this field's value rides on - only written when filterable. */
  public String chunkKey() {
    return CHUNK_KEY_PREFIX + key;
  }

  /** The chunk metadata key marking that the document carries a value for this field. */
  public String presenceChunkKey() {
    return PRESENCE_CHUNK_KEY_PREFIX + key;
  }

  /**
   * The storable form of {@code raw}, or empty when this field carries no value for it. The same
   * method runs on the value read out of a document and on a filter value, so both sides of a
   * comparison are normalized identically - a field with a pattern is an identifier and
   * case-insensitive (an address is one address however it is written), a display field is only cut
   * back to {@link #MAX_VALUE_LENGTH}.
   */
  public Optional<String> normalize(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String value = raw.strip();
    if (value.isEmpty()) {
      return Optional.empty();
    }
    if (valuePattern != null) {
      String identifier = value.toLowerCase(Locale.ROOT);
      return accepts(identifier) ? Optional.of(identifier) : Optional.empty();
    }
    return Optional.of(
        value.length() <= MAX_VALUE_LENGTH
            ? value
            : value.substring(0, MAX_VALUE_LENGTH - 1).stripTrailing() + "…");
  }

  /** Whether {@code value} is a storable value of this field - checked against the pattern. */
  public boolean accepts(String value) {
    return value != null
        && !value.isBlank()
        && value.length() <= MAX_VALUE_LENGTH
        && (valuePattern == null || valuePattern.matcher(value).matches());
  }

  private static Set<String> managedChunkKeys() {
    Set<String> keys = new java.util.LinkedHashSet<>();
    for (FormatMetadataField field : values()) {
      if (field.filterable) {
        keys.add(field.chunkKey());
        keys.add(field.presenceChunkKey());
      }
    }
    return Set.copyOf(keys);
  }
}
