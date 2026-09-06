package io.opaa.indexing.metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One freies Schlagwort of one document (metadata-schema.md, Teil II (c)). Deliberately its own
 * table rather than a row in {@code document_metadata_values}: every row there is a typisiertes
 * Feld a filter may name, and a Schlagwort must never become one. It reaches the full-text index
 * and the Kontextpräfix, never a filter, a facet, a Beleg or a Bibliotheksfeld.
 */
@Entity
@Table(name = "document_keywords")
public class DocumentKeyword {

  /** The cap per document and the cap per keyword (metadata-schema.md; Maintainer 05.09.2026). */
  public static final int MAX_KEYWORDS_PER_DOCUMENT = 5;

  public static final int MAX_KEYWORD_LENGTH = 40;

  @Id private UUID id;

  @Column(name = "document_id", nullable = false, updatable = false)
  private UUID documentId;

  @Column(name = "library_id", nullable = false, updatable = false)
  private UUID libraryId;

  @Column(name = "keyword", nullable = false, length = MAX_KEYWORD_LENGTH, updatable = false)
  private String keyword;

  @Column(name = "model_id", length = 255)
  private String modelId;

  @Column(name = "extraction_version")
  private Integer extractionVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected DocumentKeyword() {}

  public DocumentKeyword(
      UUID documentId, UUID libraryId, String keyword, String modelId, int extractionVersion) {
    this.id = UUID.randomUUID();
    this.documentId = documentId;
    this.libraryId = libraryId;
    this.keyword = keyword;
    this.modelId = modelId;
    this.extractionVersion = extractionVersion;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public String getKeyword() {
    return keyword;
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
}
