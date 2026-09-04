package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A detached copy of one {@link DocumentMetadataValue} row as it was at one moment - what an audit
 * event and the metadata view carry, independent of the managed entity's later state. {@link
 * #value()} is the machine-readable form ({@code SourceMetadataEntry.value} in the API), {@link
 * #displayValue} the German display form.
 */
public record MetadataValueSnapshot(
    String fieldKey,
    MetadataValueState state,
    String textValue,
    String vocabularyCode,
    LocalDate dateValue,
    DatePrecision datePrecision,
    MetadataOrigin origin,
    Double confidence,
    String modelId,
    Integer extractionVersion,
    UUID actorUserId,
    Instant updatedAt) {

  static MetadataValueSnapshot of(DocumentMetadataValue value) {
    return new MetadataValueSnapshot(
        value.getFieldKey(),
        value.getState(),
        value.getTextValue(),
        value.getVocabularyCode(),
        value.getDateValue(),
        value.getDatePrecision(),
        value.getOrigin(),
        value.getConfidence(),
        value.getModelId(),
        value.getExtractionVersion(),
        value.getActorUserId(),
        value.getUpdatedAt());
  }

  /** The vocabulary code, the ISO date or the text - {@code null} for a row without a value. */
  public String value() {
    if (textValue != null) {
      return textValue;
    }
    if (vocabularyCode != null) {
      return vocabularyCode;
    }
    return dateValue != null ? dateValue.toString() : null;
  }

  public String displayValue(DocumentTypeVocabulary vocabulary) {
    if (vocabularyCode != null) {
      return vocabulary.labelOf(vocabularyCode).orElse(vocabularyCode);
    }
    if (dateValue != null) {
      return MetadataValueDisplay.displayDate(dateValue, datePrecision);
    }
    return textValue;
  }

  /** Whether this row already carries exactly {@code input}'s value. */
  boolean holds(MetadataValueInput input) {
    return state == MetadataValueState.SET
        && Objects.equals(textValue, input.textValue())
        && Objects.equals(vocabularyCode, input.vocabularyCode())
        && Objects.equals(dateValue, input.dateValue())
        && Objects.equals(datePrecision, input.datePrecision());
  }
}
