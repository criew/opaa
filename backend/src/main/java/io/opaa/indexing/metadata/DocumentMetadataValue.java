package io.opaa.indexing.metadata;

import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One metadata value of one document for one field (migration 018; ADR-0024): exactly one of {@link
 * #getTextValue()}, {@link #getVocabularyCode()} and {@link #getDateValue()} is set while {@link
 * #getState()} is {@code SET}. Every row carries its {@link #getOrigin()}; a {@code MANUAL} row is
 * never touched by automatic extraction ({@link DocumentMetadataService}). The document's {@code ON
 * DELETE CASCADE} removes the row with the document.
 */
@Entity
@Table(name = "document_metadata_values")
public class DocumentMetadataValue {

  @Id private UUID id;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  @Column(name = "field_key", nullable = false, length = 100, updatable = false)
  private String fieldKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "value_state", nullable = false, length = 20)
  private MetadataValueState state = MetadataValueState.SET;

  @Column(name = "text_value", length = 1000)
  private String textValue;

  @Column(name = "vocabulary_code", length = 50)
  private String vocabularyCode;

  @Column(name = "date_value")
  private LocalDate dateValue;

  @Enumerated(EnumType.STRING)
  @Column(name = "date_precision", length = 10)
  private DatePrecision datePrecision;

  @Enumerated(EnumType.STRING)
  @Column(name = "origin", nullable = false, length = 20)
  private MetadataOrigin origin;

  @Column(name = "confidence")
  private Double confidence;

  @Column(name = "actor_user_id")
  private UUID actorUserId;

  @Column(name = "model_id", length = 255)
  private String modelId;

  @Column(name = "extraction_version")
  private Integer extractionVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected DocumentMetadataValue() {}

  private DocumentMetadataValue(UUID documentId, CoreMetadataField field) {
    this.id = UUID.randomUUID();
    this.documentId = documentId;
    this.fieldKey = field.key();
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  /** A fresh deterministic row for {@code field}; the value itself is set via {@code assign*}. */
  static DocumentMetadataValue deterministic(
      UUID documentId, CoreMetadataField field, int extractionVersion) {
    DocumentMetadataValue value = new DocumentMetadataValue(documentId, field);
    value.markDeterministic(extractionVersion);
    return value;
  }

  /**
   * A manual row for {@code field} set by {@code actorUserId}; the value is set via {@code
   * assign*}.
   */
  public static DocumentMetadataValue manual(
      UUID documentId, CoreMetadataField field, UUID actorUserId) {
    DocumentMetadataValue value = new DocumentMetadataValue(documentId, field);
    value.origin = MetadataOrigin.MANUAL;
    value.actorUserId = actorUserId;
    value.extractionVersion = null;
    value.confidence = null;
    value.modelId = null;
    return value;
  }

  /**
   * A model-derived row for {@code field} (metadata-schema.md, step 2); the value is set via {@code
   * assign*}. Kept by the deterministic extraction unless it finds a real value for the field.
   */
  public static DocumentMetadataValue derived(
      UUID documentId,
      CoreMetadataField field,
      String modelId,
      double confidence,
      int extractionVersion) {
    DocumentMetadataValue value = new DocumentMetadataValue(documentId, field);
    value.origin = MetadataOrigin.DERIVED;
    value.modelId = modelId;
    value.confidence = confidence;
    value.extractionVersion = extractionVersion;
    return value;
  }

  /**
   * Re-labels an existing row as set by hand by {@code actorUserId} (#1068): origin {@code MANUAL},
   * no confidence, model or extraction version - from now on no extraction touches it.
   */
  void markManual(UUID actorUserId) {
    this.origin = MetadataOrigin.MANUAL;
    this.actorUserId = actorUserId;
    this.extractionVersion = null;
    this.confidence = null;
    this.modelId = null;
    this.updatedAt = Instant.now();
  }

  /** Re-labels an existing (non-manual) row as the result of deterministic extraction. */
  void markDeterministic(int extractionVersion) {
    this.origin = MetadataOrigin.DETERMINISTIC;
    this.extractionVersion = extractionVersion;
    this.confidence = null;
    this.modelId = null;
    this.actorUserId = null;
    this.updatedAt = Instant.now();
  }

  public DocumentMetadataValue assignText(String text) {
    clearValue();
    this.textValue = text;
    return this;
  }

  public DocumentMetadataValue assignVocabularyCode(String code) {
    clearValue();
    this.vocabularyCode = code;
    return this;
  }

  public DocumentMetadataValue assignDate(LocalDate date, DatePrecision precision) {
    clearValue();
    this.dateValue = date;
    this.datePrecision = precision;
    return this;
  }

  /**
   * Records that a person found there is no value to find (#1069): the row stays, carries no value
   * and is only storable with origin {@code MANUAL} ({@code
   * chk_document_metadata_values_not_determinable_is_manual}). No automatic extraction ever writes
   * or clears it - {@link DocumentMetadataService} leaves every {@code MANUAL} row alone.
   */
  public DocumentMetadataValue assignNotDeterminable() {
    clearValue();
    this.state = MetadataValueState.NOT_DETERMINABLE;
    return this;
  }

  private void clearValue() {
    this.state = MetadataValueState.SET;
    this.textValue = null;
    this.vocabularyCode = null;
    this.dateValue = null;
    this.datePrecision = null;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public String getFieldKey() {
    return fieldKey;
  }

  public MetadataValueState getState() {
    return state;
  }

  public String getTextValue() {
    return textValue;
  }

  public String getVocabularyCode() {
    return vocabularyCode;
  }

  public LocalDate getDateValue() {
    return dateValue;
  }

  public DatePrecision getDatePrecision() {
    return datePrecision;
  }

  public MetadataOrigin getOrigin() {
    return origin;
  }

  public Double getConfidence() {
    return confidence;
  }

  public UUID getActorUserId() {
    return actorUserId;
  }

  public String getModelId() {
    return modelId;
  }

  public Integer getExtractionVersion() {
    return extractionVersion;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
