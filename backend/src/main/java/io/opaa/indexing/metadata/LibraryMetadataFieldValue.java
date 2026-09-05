package io.opaa.indexing.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One entry of a SELECT field's controlled value list (metadata-schema.md "Kontrolliertes Vokabular
 * statt Freitext"). The {@link #getCode()} is stable and never rewritten in place: a label may be
 * corrected freely, while removing or replacing a code takes the confirmed mapping, which is what
 * keeps "Dokument trägt einen Wert, den es im Schema nicht mehr gibt" unreachable - the document
 * rows reference this row by foreign key with {@code ON DELETE RESTRICT}.
 */
@Entity
@Table(name = "library_metadata_field_values")
public class LibraryMetadataFieldValue {

  @Id private UUID id;

  @Column(name = "field_id", nullable = false, updatable = false)
  private UUID fieldId;

  @Column(name = "code", nullable = false, length = 50, updatable = false)
  private String code;

  @Column(name = "label", nullable = false, length = 100)
  private String label;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  protected LibraryMetadataFieldValue() {}

  LibraryMetadataFieldValue(UUID fieldId, String code, String label, int sortOrder) {
    this.id = UUID.randomUUID();
    this.fieldId = fieldId;
    this.code = code;
    this.label = label;
    this.sortOrder = sortOrder;
  }

  void relabel(String label) {
    this.label = label;
  }

  void reorder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public UUID getId() {
    return id;
  }

  public UUID getFieldId() {
    return fieldId;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public int getSortOrder() {
    return sortOrder;
  }
}
