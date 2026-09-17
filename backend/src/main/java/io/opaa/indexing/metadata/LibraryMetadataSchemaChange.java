package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataSchemaChangeKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One running schema change of a library (metadata-schema.md, "Nachlauf im Betrieb"): a list value
 * being mapped onto another one or onto "leer", or a field being deleted. The row exists exactly
 * while the change runs and is what retires its subject: no document may be given a retired value
 * or a value of a retired field, while every document that already carries one keeps a value the
 * schema still lists - which is what makes "Dokument trägt einen Wert, den es im Schema nicht mehr
 * gibt" unreachable even during the run.
 *
 * <p>The {@link #getCorrelationRef()} is drawn once, at the confirmation, so every audit event of
 * the change reads back from the audit log as one operation across all of its Chargen.
 */
@Entity
@Table(name = "library_metadata_schema_changes")
public class LibraryMetadataSchemaChange {

  @Id private UUID id;

  @Column(name = "field_id", nullable = false, updatable = false)
  private UUID fieldId;

  @Column(name = "value_id", updatable = false)
  private UUID valueId;

  @Column(name = "target_value_id", updatable = false)
  private UUID targetValueId;

  @Enumerated(EnumType.STRING)
  @Column(name = "change_kind", nullable = false, length = 20, updatable = false)
  private LibraryMetadataSchemaChangeKind kind;

  @Column(name = "correlation_ref", nullable = false, length = 100, updatable = false)
  private String correlationRef;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private Instant requestedAt;

  @Column(name = "processed_documents", nullable = false)
  private long processedDocuments;

  protected LibraryMetadataSchemaChange() {}

  private LibraryMetadataSchemaChange(
      UUID fieldId,
      UUID valueId,
      UUID targetValueId,
      LibraryMetadataSchemaChangeKind kind,
      String correlationRef) {
    this.id = UUID.randomUUID();
    this.fieldId = fieldId;
    this.valueId = valueId;
    this.targetValueId = targetValueId;
    this.kind = kind;
    this.correlationRef = correlationRef;
    this.requestedAt = Instant.now();
    this.processedDocuments = 0;
  }

  static LibraryMetadataSchemaChange valueRemap(
      UUID fieldId, UUID valueId, UUID targetValueId, String correlationRef) {
    return new LibraryMetadataSchemaChange(
        fieldId,
        valueId,
        targetValueId,
        LibraryMetadataSchemaChangeKind.VALUE_REMAP,
        correlationRef);
  }

  static LibraryMetadataSchemaChange fieldDeletion(UUID fieldId, String correlationRef) {
    return new LibraryMetadataSchemaChange(
        fieldId, null, null, LibraryMetadataSchemaChangeKind.FIELD_DELETION, correlationRef);
  }

  void countProcessedDocument() {
    processedDocuments++;
  }

  public UUID getId() {
    return id;
  }

  public UUID getFieldId() {
    return fieldId;
  }

  public UUID getValueId() {
    return valueId;
  }

  public UUID getTargetValueId() {
    return targetValueId;
  }

  public LibraryMetadataSchemaChangeKind getKind() {
    return kind;
  }

  public String getCorrelationRef() {
    return correlationRef;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public long getProcessedDocuments() {
    return processedDocuments;
  }
}
