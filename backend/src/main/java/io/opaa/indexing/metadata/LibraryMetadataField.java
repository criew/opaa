package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataFieldType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One field a library defines beside the built-in core fields (metadata-schema.md Teil II (b)): its
 * stable key, German label, type and the Wirkstellen it serves. The Aufnahmeregel is an invariant
 * of this entity and of the table beneath it - a field that serves neither the filter nor the
 * Kontextpräfix does not exist; "nur Beleg-Anzeige" is not a storable state. At most two fields of
 * a library carry a {@link #getCitationPosition()} (1 or 2), which is what keeps the Belegzeile
 * readable.
 */
@Entity
@Table(name = "library_metadata_fields")
public class LibraryMetadataField {

  @Id private UUID id;

  @Column(name = "library_id", nullable = false, updatable = false)
  private UUID libraryId;

  @Column(name = "field_key", nullable = false, length = 50, updatable = false)
  private String fieldKey;

  @Column(name = "label", nullable = false, length = 100)
  private String label;

  @Enumerated(EnumType.STRING)
  @Column(name = "field_type", nullable = false, length = 20, updatable = false)
  private LibraryMetadataFieldType type;

  @Column(name = "value_pattern", length = 200)
  private String valuePattern;

  @Column(name = "filter_enabled", nullable = false)
  private boolean filterEnabled;

  @Column(name = "context_prefix_enabled", nullable = false)
  private boolean contextPrefixEnabled;

  @Column(name = "citation_enabled", nullable = false)
  private boolean citationEnabled;

  @Column(name = "citation_position")
  private Integer citationPosition;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected LibraryMetadataField() {}

  LibraryMetadataField(
      UUID libraryId,
      String fieldKey,
      LibraryMetadataFieldType type,
      String valuePattern,
      int sortOrder) {
    this.id = UUID.randomUUID();
    this.libraryId = libraryId;
    this.fieldKey = fieldKey;
    this.type = type;
    this.valuePattern = valuePattern;
    this.sortOrder = sortOrder;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  /**
   * Applies label and Wirkstellen; the caller has already checked that at least one retrieval
   * effect is named ({@link LibraryMetadataFieldService}) - the database check is the second guard,
   * not the first message.
   */
  void apply(
      String label, boolean filterEnabled, boolean contextPrefixEnabled, Integer citationPosition) {
    this.label = label;
    this.filterEnabled = filterEnabled;
    this.contextPrefixEnabled = contextPrefixEnabled;
    this.citationPosition = citationPosition;
    this.citationEnabled = citationPosition != null;
    this.updatedAt = Instant.now();
  }

  /** The {@code document_metadata_values.field_key} of this field. */
  public String documentFieldKey() {
    return LibraryMetadataFieldKeys.documentFieldKey(fieldKey);
  }

  /** The chunk metadata key this field's value rides on - only written when filterable. */
  public String chunkKey() {
    return LibraryMetadataFieldKeys.chunkKey(fieldKey);
  }

  public UUID getId() {
    return id;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public String getFieldKey() {
    return fieldKey;
  }

  public String getLabel() {
    return label;
  }

  public LibraryMetadataFieldType getType() {
    return type;
  }

  public String getValuePattern() {
    return valuePattern;
  }

  public boolean isFilterEnabled() {
    return filterEnabled;
  }

  public boolean isContextPrefixEnabled() {
    return contextPrefixEnabled;
  }

  public boolean isCitationEnabled() {
    return citationEnabled;
  }

  public Integer getCitationPosition() {
    return citationPosition;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
