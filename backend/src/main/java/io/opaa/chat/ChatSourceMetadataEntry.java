package io.opaa.chat;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.indexing.metadata.CitationFieldValue;
import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.CoreMetadataField;
import io.opaa.indexing.metadata.MetadataValueDisplay;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One metadata value of a cited document as persisted on a chat turn's source snapshot (ADR-0024;
 * Maintainer-Beschluss vom 04.09.2026 am Epic): self-describing - German label, display text,
 * machine-readable value, origin - so the Beleg renders it without any field knowledge, and a
 * library field is just another entry. The chat's own copy: a later change to the indexing record
 * never rewrites what {@code chat_messages.sources} already holds.
 */
public record ChatSourceMetadataEntry(
    String fieldKey,
    String label,
    String value,
    String displayValue,
    MetadataOrigin origin,
    DatePrecision datePrecision,
    boolean detailOnly) {

  /** An entry the Fundstellenzeile shows - every core field and most other fields. */
  public ChatSourceMetadataEntry(
      String fieldKey,
      String label,
      String value,
      String displayValue,
      MetadataOrigin origin,
      DatePrecision datePrecision) {
    this(fieldKey, label, value, displayValue, origin, datePrecision, false);
  }

  /** The core fields of {@code core} in schema order; empty (not null-padded) for absent fields. */
  public static List<ChatSourceMetadataEntry> fromCore(CoreMetadata core) {
    List<ChatSourceMetadataEntry> entries = new ArrayList<>(3);
    if (core == null) {
      return entries;
    }
    if (core.title() != null) {
      entries.add(
          new ChatSourceMetadataEntry(
              CoreMetadataField.TITLE.key(),
              CoreMetadataField.TITLE.label(),
              core.title(),
              core.title(),
              core.titleOrigin(),
              null));
    }
    if (core.documentTypeCode() != null) {
      entries.add(
          new ChatSourceMetadataEntry(
              CoreMetadataField.DOCUMENT_TYPE.key(),
              CoreMetadataField.DOCUMENT_TYPE.label(),
              core.documentTypeCode(),
              core.documentTypeLabel() != null ? core.documentTypeLabel() : core.documentTypeCode(),
              core.documentTypeOrigin(),
              null));
    }
    if (core.documentDate() != null) {
      entries.add(
          new ChatSourceMetadataEntry(
              CoreMetadataField.DOCUMENT_DATE.key(),
              CoreMetadataField.DOCUMENT_DATE.label(),
              core.documentDate().toString(),
              displayDate(core.documentDate(), core.documentDatePrecision()),
              core.documentDateOrigin(),
              core.documentDatePrecision()));
    }
    return entries;
  }

  /**
   * metadata-schema.md: "höchstens zwei Bibliotheksfelder" beside the core fields - the cap of the
   * Belegzeile, counted over every non-core entry it actually shows.
   */
  public static final int MAX_LINE_FIELDS = 2;

  /**
   * The core fields of {@code core} followed by {@code citationFields} - a document's format fields
   * and then the library fields with a citation position. Two rules, in this order: a field whose
   * display text a preceding entry already shows verbatim is left out (a mail's Betreff is also its
   * Titel, and a Belegzeile that says the same thing twice reads worse, not richer), and only then
   * does the cap of {@link #MAX_LINE_FIELDS} non-core entries take effect - a dropped duplicate
   * must not cost the place a library field could have had. A {@code detailOnly} entry is not on
   * the line and does not count against the cap.
   */
  public static List<ChatSourceMetadataEntry> from(
      CoreMetadata core, List<CitationFieldValue> citationFields) {
    List<ChatSourceMetadataEntry> entries = fromCore(core);
    if (citationFields == null) {
      return entries;
    }
    int lineFields = 0;
    for (CitationFieldValue field : citationFields) {
      if (entries.stream()
          .anyMatch(
              entry -> java.util.Objects.equals(entry.displayValue(), field.displayValue()))) {
        continue;
      }
      if (!field.detailOnly()) {
        if (lineFields >= MAX_LINE_FIELDS) {
          continue;
        }
        lineFields++;
      }
      entries.add(
          new ChatSourceMetadataEntry(
              field.fieldKey(),
              field.label(),
              field.value(),
              field.displayValue(),
              field.origin(),
              field.datePrecision(),
              field.detailOnly()));
    }
    return entries;
  }

  /** "12.03.2026", "03/2026" or "2024" - a date at its own precision, never a padded day. */
  static String displayDate(LocalDate date, DatePrecision precision) {
    return MetadataValueDisplay.displayDate(date, precision);
  }
}
